/**
 * 职责：中央组装点——全应用状态（页面路由、账号/分组、DEK、生效设置、备份授权）全部集中于此。
 *       无 ViewModel：页面组件一律无状态、只收数据 props 与事件回调，数据流单向
 *       （状态下发、事件上传）。解锁/生物识别、备份导入导出、ON_STOP 自动锁定、
 *       DataStore 读写与 appsettings.json 外挂覆盖也都装配在这里。
 * 架构位置：MainActivity 的 setContent → 本函数 → when(page) 渲染 page/ 各屏。
 *           加新页面 = navigation/AppPage 加分支 + 本文件 when 加一支。
 * Python 类比：≈ 一个顶层 App 类——所有实例属性、所有事件处理方法都放这儿，
 *           各页面只是纯渲染函数；remember ≈ 给「函数局部变量」挂跨重渲染的缓存。
 */
package com.copy.account

import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.copy.account.data.backup.BackupNameFactory
import com.copy.account.data.backup.deleteBackupFile
import com.copy.account.data.backup.deleteFileBackup
import com.copy.account.data.backup.listBackupFiles
import com.copy.account.data.backup.listFileBackups
import com.copy.account.data.backup.plainExportTimestamp
import com.copy.account.data.backup.readFileBackup
import com.copy.account.data.backup.readSelectedDocument
import com.copy.account.data.backup.uniqueBackupName
import com.copy.account.data.backup.uniquePlainExportName
import com.copy.account.data.backup.writeBackupFile
import com.copy.account.data.backup.writeFileBackup
import com.copy.account.data.model.Account
import com.copy.account.data.model.FIXED_GROUP_COUNT
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.MAX_AUTO_LOCK_MINUTES
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.SavedTheme
import com.copy.account.data.model.accountInGroup
import com.copy.account.data.model.initialGroups
import com.copy.account.navigation.AppPage
import com.copy.account.page.BackupDirectoryOps
import com.copy.account.page.BackupExporter
import com.copy.account.page.BackupFileStore
import com.copy.account.page.BackupRowUi
import com.copy.account.runtime.IdleLockHost
import com.copy.account.runtime.rememberBackupLocationState
import com.copy.account.runtime.rememberSettingsContainer
import com.copy.account.runtime.rememberUnlockSession
import com.copy.account.page.AboutScreen
import com.copy.account.page.AccountDetailScreen
import com.copy.account.page.AccountEditScreen
import com.copy.account.page.AppearanceSettingsScreen
import com.copy.account.page.BackupScreen
import com.copy.account.page.GroupManageScreen
import com.copy.account.page.HomeScreen
import com.copy.account.page.SecuritySettingsScreen
import com.copy.account.page.SettingsScreen
import com.copy.account.page.UnlockScreen
import com.copy.account.security.AccExportInput
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.SecureVaultStore
import com.copy.account.security.exportAcc
import com.copy.account.security.exportPlainJson
import com.copy.account.security.importAcc
import com.copy.account.ui.theme.parseThemeJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountApp(
    themeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    onThemeModeChange: (String) -> Unit = {},
    accentTheme: String = "green",
    onAccentThemeChange: (String) -> Unit = {},
    customThemeJson: String = "",
    onCustomThemeJsonChange: (String) -> Unit = {},
    allowScreenshots: Boolean = false,
    onAllowScreenshotsChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    // remember { ... }：首次组合求值一次、之后重渲染复用同一实例（按调用点缓存）。
    // SecureVaultStore 只包着 prefs 与文件路径、不持密钥，常驻内存安全。
    val store = remember { SecureVaultStore(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<AppPage>(AppPage.Unlock) }
    /** 主页速览面板展开的账号 id；内存级（rememberSaveable 扛旋转/进程重建），锁屏重解锁后保持展开。 */
    var previewAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    /** 长按“作为模板新建”时临时携带的模板；进入编辑页后预填但按新账号保存。 */
    var editTemplate by remember { mutableStateOf<Account?>(null) }

    // ===== 设置域（P1 抽取）：状态与 DataStore 读写全部收敛在 SettingsContainer =====
    // onThemeApplied：设置生效值变化时把 4 个窗口级主题项回推 MainActivity（原冷启动/reload 的四次回调合一）。
    val settingsContainer = rememberSettingsContainer(
        initialCustomThemeJson = customThemeJson,
        initialThemeMode = themeMode,
        initialAccentTheme = accentTheme,
        initialAllowScreenshots = allowScreenshots,
        onThemeApplied = { applied ->
            onThemeModeChange(applied.themeMode)
            onAccentThemeChange(applied.accentTheme)
            onCustomThemeJsonChange(applied.customThemeJson)
            onAllowScreenshotsChange(applied.allowScreenshots)
        }
    )
    val settings = settingsContainer.settings
    val maskChar = settingsContainer.maskChar

    // ===== 解锁会话域（P3 抽取）：DEK/账号数据/生物识别收敛在 UnlockSession =====
    val session = rememberUnlockSession(
        store = store,
        biometricEnabled = { settingsContainer.settings.biometricEnabled },
        onBiometricEnabledChange = { enabled -> settingsContainer.update { it.copy(biometricEnabled = enabled) } },
        onUnlocked = { page = AppPage.Home },
        onLocked = { editTemplate = null; page = AppPage.Unlock }
    )
    val accounts = session.accounts
    val groups = session.groups
    val selectedGroupId = session.selectedGroupId
    val dataKey = session.dataKey
    val passwordConfigured = session.passwordConfigured

    // ===== 备份目录/授权域（P1 抽取）：路径、授权、提示状态收敛在 BackupLocationState =====
    val backupLocation = rememberBackupLocationState()
    val directBackup = backupLocation.directBackup

    /** 当前是否处于前台 RESUME 状态，用于在回到前台时触发一次生物识别。 */
    var resumed by remember { mutableStateOf(false) }

    // 声明式版 startActivityForResult：rememberLauncherForActivityResult 注册「意图+回调」，
    // launch() 发出（这里是 SAF 选目录树），用户操作完在回调里拿结果（content URI）。
    val chooseBackupDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        backupLocation.onDirectoryChosen(uri)
    }

    fun requestBackupDirectory() {
        backupLocation.clearMessage()
        chooseBackupDirectory.launch(null)
    }

    /** 改密成功后须重缓存导出密钥（改密会废弃旧缓存）；数据本体不动（DEK 不变）。 */
    suspend fun changeMasterPassword(newPassword: String): Result<Unit> {
        val key = session.dataKey ?: return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
        return withContext(Dispatchers.Default) {
            store.changeMasterPassword(newPassword, key).onSuccess { material ->
                try {
                    store.cacheBackupKey(material, key)
                } finally {
                    material.clear()
                }
            }.map { }
        }
    }

    // 手写返回栈：无导航框架，系统返回键在此映射回上级页；enabled 让一级页（Unlock/Home）不拦截。
    BackHandler(enabled = page != AppPage.Unlock && page != AppPage.Home) {
        page = when (page) {
            AppPage.Settings, AppPage.Groups, is AppPage.Detail, is AppPage.Edit -> AppPage.Home
            AppPage.SecuritySettings, AppPage.AppearanceSettings, AppPage.About, AppPage.BackupFiles -> AppPage.Settings
            else -> page
        }
    }

    // 统一生命周期：进入后台立即锁定；回到前台且仍处于解锁页时触发一次生物识别。
    // DisposableEffect ≈ setup/teardown 的 context manager：进入组合时注册生命周期观察者，
    // onDispose（离开组合）时注销，防止泄漏。
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    // 从系统设置授予/撤销「所有文件访问」后返回时刷新
                    backupLocation.refreshStorageAccess()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    session.onAppPause()
                }
                Lifecycle.Event.ON_STOP -> session.onAppStop()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // 前台空闲自动锁定（P1 抽取）：全部接线收敛在 runtime/IdleLockHost——
    // Handler 排程 + uptimeMillis 时钟 + userInteractionSink 挂载 + 启停，闭包陈旧防御也在那边。
    IdleLockHost(
        activity = activity,
        isUnlocked = dataKey != null,
        autoLockMinutes = settings.autoLockMinutes,
        onLock = session::lockApp
    )

    // 解锁页 + 已恢复前台 + 已配置生物识别 => 自动弹出指纹；避免后台/前台切换的时序竞争。
    LaunchedEffect(page, resumed, passwordConfigured, settings.biometricEnabled) {
        if (page == AppPage.Unlock && resumed && passwordConfigured && settings.biometricEnabled) {
            delay(300)
            if (page == AppPage.Unlock && resumed) session.authenticateBiometric()
        }
    }

    fun createGroup(name: String): String {
        val id = "custom-${System.currentTimeMillis()}"
        session.groups = session.groups + Group(id, name.trim(), GroupKind.CUSTOM)
        // 立即保存分组，避免用户离开账号编辑页但未保存账号时丢失分组。
        session.persistVault()
        return id
    }

    when (val current = page) {
        AppPage.Unlock -> UnlockScreen(
            firstUse = !passwordConfigured,
            biometricEnabled = settings.biometricEnabled && store.biometricAvailable(),
            resetKey = session.lockGeneration,
            onBiometricUnlock = { session.authenticateBiometric() },
            // PBKDF2 派生 + 文件读写 + 导出密钥缓存在 UnlockSession.unlockWithPassword 内后台线程完成。
            onUnlock = { password -> session.unlockWithPassword(password) }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            previewAccountId = previewAccountId,
            onPreviewAccountIdChange = { previewAccountId = it },
            onGroupSelected = { session.selectedGroupId = it; session.persistVault() },
            onNewAccount = { editTemplate = null; page = AppPage.Edit(null) },
            onEditAccount = { editTemplate = null; page = AppPage.Edit(it) },
            onTemplateNew = { editTemplate = it; page = AppPage.Edit(null) },
            onDeleteAccount = { id ->
                session.accounts = session.accounts.filterNot { it.id == id }
                session.persistVault()
            },
            onMoveAccounts = { ids, fromGroupId, toGroupId ->
                val from = groups.firstOrNull { it.id == fromGroupId }
                val to = groups.firstOrNull { it.id == toGroupId }
                session.accounts = accounts.map { account ->
                    if (account.id !in ids) account
                    else account.copy(
                        groups = when {
                            // 移入默认（未分组）= 清空全部自定义归属；源自定义组则移出再入目标；动态组作源仅添加归属。
                            to?.kind == GroupKind.DEFAULT -> emptySet()
                            from?.kind == GroupKind.CUSTOM -> account.groups - fromGroupId + toGroupId
                            else -> account.groups + toGroupId
                        }
                    )
                }
                session.persistVault()
            },
            onManageGroups = { page = AppPage.Groups },
            onOpenSettings = { page = AppPage.Settings },
            onOpenDetail = { page = AppPage.Detail(it) },
            onHotpAdvance = session::advanceHotp
        )

        AppPage.Groups -> GroupManageScreen(
            groups = groups,
            onBack = { page = AppPage.Home },
            onAddGroup = { val id = createGroup(it); session.persistVault(); id },
            onRenameGroup = { id, name -> session.groups = session.groups.map { if (it.id == id) it.copy(name = name) else it }; session.persistVault() },
            onDeleteGroup = { id ->
                session.groups = session.groups.filterNot { it.id == id }
                session.accounts = session.accounts.map { it.copy(groups = it.groups - id) }
                if (selectedGroupId == id) session.selectedGroupId = "default"
                session.persistVault()
            },
            accountCount = { id ->
                // 单一来源：与 HomeScreen 筛选共用 accountInGroup（Models.kt）。
                accounts.count { accountInGroup(it, groups, id) }
            },
            onMoveCustomGroup = { id, direction ->
                val fixed = groups.take(FIXED_GROUP_COUNT)
                val custom = session.groups.drop(FIXED_GROUP_COUNT).toMutableList()
                val index = custom.indexOfFirst { it.id == id }
                val target = index + direction
                if (index >= 0 && target in custom.indices) {
                    val item = custom.removeAt(index)
                    custom.add(target, item)
                    session.groups = fixed + custom
                    session.persistVault()
                }
            }
        )

        AppPage.Settings -> SettingsScreen(
            onOpenSecurity = { page = AppPage.SecuritySettings },
            onOpenAppearance = { page = AppPage.AppearanceSettings },
            onOpenBackup = { page = AppPage.BackupFiles },
            onOpenAbout = { page = AppPage.About },
            onBack = { page = AppPage.Home }
        )

        AppPage.SecuritySettings -> SecuritySettingsScreen(
            biometricEnabled = settings.biometricEnabled,
            biometricAvailable = store.biometricAvailable(),
            onToggleBiometric = session::configureBiometric,
            onChangeMasterPassword = ::changeMasterPassword,
            autoLockMinutes = settings.autoLockMinutes,
            onAutoLockChange = { minutes ->
                settingsContainer.update { it.copy(autoLockMinutes = minutes.coerceIn(0, MAX_AUTO_LOCK_MINUTES)) }
            },
            onBack = { page = AppPage.Settings },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onClipboardClearChange = { seconds ->
                settingsContainer.update { it.copy(clipboardClearSeconds = seconds.coerceIn(0, 86_400)) }
            },
            blockScreenshots = !settings.allowScreenshots,
            onBlockScreenshotsChange = { blocked ->
                val allow = !blocked
                settingsContainer.update { it.copy(allowScreenshots = allow) }
                onAllowScreenshotsChange(allow)
            }
        )

        AppPage.AppearanceSettings -> AppearanceSettingsScreen(
            themeMode = settings.themeMode,
            onThemeModeChange = { mode ->
                val normalized = mode.lowercase().let { if (it == "light" || it == "system") it else "dark" }
                settingsContainer.update { it.copy(themeMode = normalized) }
                onThemeModeChange(normalized)
            },
            accentTheme = settings.accentTheme,
            onAccentThemeChange = { accent ->
                val normalized = if (accent == "blue") "blue" else "green"
                // 选择内置配色时退出 JSON 自定义主题，避免两个色板同时生效。
                settingsContainer.update { it.copy(accentTheme = normalized, customThemeJson = "") }
                onAccentThemeChange(normalized)
                onCustomThemeJsonChange("")
            },
            customThemeJson = settings.customThemeJson,
            customThemes = settings.customThemes,
            onApplyThemeJson = { json ->
                val parsed = parseThemeJson(json)
                if (parsed == null) {
                    false
                } else {
                    val mode = parsed.defaultMode
                    settingsContainer.update { it.copy(customThemeJson = json.trim(), themeMode = mode) }
                    onCustomThemeJsonChange(json.trim())
                    onThemeModeChange(mode)
                    true
                }
            },
            onSaveCustomTheme = { name, json ->
                val parsed = parseThemeJson(json)
                if (parsed != null) {
                    val saved = SavedTheme("custom-${System.currentTimeMillis()}", name.ifBlank { parsed.name }, json.trim())
                    settingsContainer.update { it.copy(customThemes = (it.customThemes + saved).distinctBy { it.id }) }
                }
            },
            onDeleteCustomTheme = { id ->
                settingsContainer.update { it.copy(customThemes = it.customThemes.filterNot { t -> t.id == id }) }
            },
            onReloadSettings = { settingsContainer.reload(directBackup, backupLocation.backupTreeUri, backupLocation.backupFolder) },
            externalConfigLoaded = settingsContainer.appSettingsOverride != null,
            onBack = { page = AppPage.Settings }
        )

        AppPage.About -> AboutScreen(onBack = { page = AppPage.Settings })

        AppPage.BackupFiles -> {
            // ===== 备份域接线（P2 收拢）：BackupScreen 只见三接口，IO/加解密/线程全在此实现 =====
            // 接口实现对象 remember 一次，内部经 current* 现读状态，防闭包陈旧（同 IdleLock 的教训）。
            val currentAccounts by rememberUpdatedState(accounts)
            val currentGroups by rememberUpdatedState(groups)
            val currentSelectedGroupId by rememberUpdatedState(selectedGroupId)
            val currentSettings by rememberUpdatedState(settings)
            val currentDataKey by rememberUpdatedState(dataKey)

            /**
             * 加密导出与明文导出共用的门禁。刻意只写一份：明文路径风险更高（全部密码无加密落盘），
             * 若各维护一份，将来加第 4 条条件时漏改明文那份，就是一次静默的安全绕过。
             */
            fun exportGateError(): String? = when {
                directBackup && !backupLocation.storageAccessGranted -> "请先授予「所有文件访问」权限"
                !directBackup && backupLocation.backupTreeUri == null -> "请先授权备份目录"
                currentDataKey == null -> "当前未解锁，请重新解锁后重试"
                else -> null
            }

            /** 落盘分派：直写轨只有命名器，SAF 轨还要 MIME。两条轨的差异收在这里。 */
            fun writeExportBytes(bytes: ByteArray, nameFactory: BackupNameFactory, mimeType: String): String {
                val tree = backupLocation.backupTreeUri?.let(Uri::parse)
                return if (directBackup) writeFileBackup(bytes, backupLocation.backupFolder, nameFactory)
                else writeBackupFile(context, tree!!, bytes, backupLocation.backupFolder, nameFactory, mimeType)
            }

            val fileStore = remember {
                object : BackupFileStore {
                    override fun refresh(onResult: (List<BackupRowUi>) -> Unit) {
                        scope.launch {
                            val rows = withContext(Dispatchers.IO) {
                                runCatching {
                                    val directRows = if (directBackup) {
                                        listFileBackups(backupLocation.backupFolder).getOrDefault(emptyList())
                                    } else emptyList()
                                    val safRows = if (!directBackup) {
                                        listBackupFiles(context, backupLocation.backupTreeUri, backupLocation.backupFolder).getOrDefault(emptyList())
                                    } else emptyList()
                                    (directRows.map {
                                        BackupRowUi(it.file.absolutePath, it.file.name, it.size, it.modified, direct = true)
                                    } + safRows.map {
                                        BackupRowUi(it.file.uri.toString(), it.file.name ?: "", it.size, it.modified, direct = false)
                                    }).sortedByDescending { it.modified }
                                }.getOrDefault(emptyList())
                            }
                            onResult(rows)
                        }
                    }

                    override fun read(row: BackupRowUi): ByteArray? =
                        if (row.direct) readFileBackup(File(row.key)).getOrNull()
                        else readSelectedDocument(context, Uri.parse(row.key)).getOrNull()

                    override fun delete(row: BackupRowUi, onDone: (Boolean) -> Unit) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (row.direct) deleteFileBackup(File(row.key))
                                    else deleteBackupFile(context, Uri.parse(row.key))
                                }.isSuccess
                            }
                            onDone(ok)
                        }
                    }
                }
            }

            val exporter = remember {
                object : BackupExporter {
                    override fun exportEncrypted(onDone: (Result<String>) -> Unit) {
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                val gateError = exportGateError()
                                if (gateError != null) {
                                    Result.failure(IllegalStateException(gateError))
                                } else {
                                    val vaultKey = currentDataKey!!.copyOf()
                                    runCatching {
                                        val material = store.loadCachedBackupKey(vaultKey) ?: throw ExportPasswordRequiredException()
                                        try {
                                            val bytes = exportAcc(
                                                AccExportInput(
                                                    PersistedVault(
                                                        accounts = currentAccounts,
                                                        groups = currentGroups,
                                                        selectedGroupId = currentSelectedGroupId
                                                    ),
                                                    currentSettings
                                                ), material.key, material.salt, material.iterations
                                            )
                                            try {
                                                writeExportBytes(bytes, ::uniqueBackupName, "application/octet-stream")
                                            } finally {
                                                bytes.fill(0)
                                            }
                                        } finally {
                                            material.clear()
                                        }
                                    }.also { vaultKey.fill(0) }
                                }
                            }
                            onDone(result)
                        }
                    }

                    override fun exportPlaintext(onDone: (Result<String>) -> Unit) {
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                // 门禁与加密导出共用 exportGateError()。明文导出不需要 KEK：账号明文此刻就在
                                // accounts/groups 里，所以不碰 loadCachedBackupKey，也就不会触发
                                // ExportPasswordRequiredException 那个「首次导出输密码」的分支。
                                val gateError = exportGateError()
                                if (gateError != null) {
                                    Result.failure(IllegalStateException(gateError))
                                } else {
                                    runCatching {
                                        val bytes = exportPlainJson(
                                            PersistedVault(
                                                accounts = currentAccounts,
                                                groups = currentGroups,
                                                selectedGroupId = currentSelectedGroupId
                                            ),
                                            plainExportTimestamp()
                                        )
                                        // 命名与 mime 都与 .acc 分开：文件名带 plain 前缀，SAF 轨用 application/json
                                        // 让文件管理器把扩展名和类型对上。写完立刻清零字节，尽量缩短明文在堆上的时间。
                                        try {
                                            writeExportBytes(bytes, ::uniquePlainExportName, "application/json")
                                        } finally {
                                            bytes.fill(0)
                                        }
                                    }
                                }
                            }
                            onDone(result)
                        }
                    }

                    override fun prepareExport(password: String): Result<Unit> {
                        if (currentDataKey == null) {
                            return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
                        }
                        val unlocked = store.unlockWithPassword(password)
                        return if (unlocked == null) {
                            Result.failure(IllegalArgumentException("主密码错误"))
                        } else {
                            try {
                                store.cacheBackupKey(unlocked.backupKey, unlocked.dataKey)
                            } finally {
                                unlocked.backupKey.clear()
                                unlocked.dataKey.fill(0)
                            }
                        }
                    }
                }
            }

            val directoryOps = remember {
                object : BackupDirectoryOps {
                    override val directBackup get() = backupLocation.directBackup
                    override val storageAccessGranted get() = backupLocation.storageAccessGranted
                    override val backupTreeUri get() = backupLocation.backupTreeUri
                    override val backupFolder get() = backupLocation.backupFolder
                    override val directoryMessage get() = backupLocation.backupDirectoryMessage
                    override fun changeBackupFolder(raw: String) = backupLocation.changeBackupFolder(raw)
                    override fun chooseDirectory() = requestBackupDirectory()
                    override fun requestStorageAccess() { backupLocation.requestStorageAccess() }
                }
            }

                BackupScreen(
                fileStore = fileStore,
                exporter = exporter,
                directory = directoryOps,
                accountCount = accounts.size,
                groupCount = groups.size,
                onImport = { bytes, password ->
                    importAcc(bytes, password)
                },
                onApplyImport = { imported ->
                    session.accounts = imported.vault.accounts
                    session.groups = imported.vault.groups.ifEmpty { initialGroups }
                    session.selectedGroupId = imported.vault.selectedGroupId.ifBlank { "default" }
                    // 生物识别包装密钥属于当前设备，备份不携带它；保留本机开关，使已启用的指纹解锁在导入后仍可用。
                    val importedSettings = imported.settings.copy(biometricEnabled = settingsContainer.baseSettings.biometricEnabled)
                    settingsContainer.update { importedSettings }
                    session.persistVault()
                    // 设置整体被备份替换，窗口级主题四项随之同步（update 本身不回推，与原 persistSettings 语义一致）。
                    onThemeModeChange(importedSettings.themeMode)
                    onAccentThemeChange(importedSettings.accentTheme)
                    onCustomThemeJsonChange(importedSettings.customThemeJson)
                    onAllowScreenshotsChange(importedSettings.allowScreenshots)
                    page = AppPage.Home
                },
                onBack = { page = AppPage.Settings }
            )
        }

        is AppPage.Detail -> AccountDetailScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            onBack = { page = AppPage.Home },
            onEdit = { page = AppPage.Edit(current.accountId) },
            onHotpAdvance = session::advanceHotp
        )

        is AppPage.Edit -> AccountEditScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            template = editTemplate,
            groups = groups,
            initialGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            onBack = { page = AppPage.Home; editTemplate = null },
            onCreateGroup = ::createGroup,
            onSave = { edited ->
                val nextAccounts = if (accounts.any { it.id == edited.id }) {
                    accounts.map { if (it.id == edited.id) edited else it }
                } else {
                    accounts + edited
                }
                session.saveVault(nextAccounts = nextAccounts).onSuccess {
                    session.accounts = nextAccounts
                    editTemplate = null
                    page = AppPage.Home
                }
            },
            onDelete = current.accountId?.let { id ->
                {
                    val nextAccounts = accounts.filterNot { it.id == id }
                    session.saveVault(nextAccounts = nextAccounts).onSuccess {
                        session.accounts = nextAccounts
                        page = AppPage.Home
                    }
                }
            }
        )
    }
}
