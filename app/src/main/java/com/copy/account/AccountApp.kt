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
import android.os.Build
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
import com.copy.account.data.backup.readFileBackup
import com.copy.account.data.backup.readSelectedDocument
import com.copy.account.data.backup.uniqueBackupName
import com.copy.account.data.backup.uniquePlainExportName
import com.copy.account.data.backup.writeFileBackup
import com.copy.account.data.backup.writeBackupFile
import com.copy.account.data.backup.writeFileBackup
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.FIXED_GROUP_COUNT
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.security.MAX_PASSWORD_ITERATIONS
import com.copy.account.security.MIN_PASSWORD_ITERATIONS
import com.copy.account.data.model.PersistedVault
import com.copy.account.security.PasskeyRegistry
import com.copy.account.security.PasskeyVaultAccess
import com.copy.account.security.parseImportPayload
import com.copy.account.page.PasskeyDisplayEntry
import com.copy.account.data.model.SavedTheme
import com.copy.account.data.model.accountInGroup
import com.copy.account.data.model.initialGroups
import com.copy.account.navigation.AppPage
import com.copy.account.page.BackupDirectoryOps
import com.copy.account.page.BackupExporter
import com.copy.account.page.BackupFileStore
import com.copy.account.page.BackupRowUi
import com.copy.account.runtime.IdleLockHost
import com.copy.account.runtime.AutofillBridge
import com.copy.account.runtime.LogEvents
import com.copy.account.runtime.appendLog
import com.copy.account.runtime.rememberBackupLocationState
import com.copy.account.runtime.rememberSettingsContainer
import com.copy.account.runtime.rememberUnlockSession
import com.copy.account.runtime.ExtensionWiring
import com.copy.account.runtime.writeSyncPointer
import com.copy.account.page.AboutScreen
import com.copy.account.page.AccountDetailScreen
import com.copy.account.page.AccountEditScreen
import com.copy.account.page.AppearanceSettingsScreen
import com.copy.account.page.BackupScreen
import com.copy.account.page.GroupManageScreen
import com.copy.account.page.HomeScreen
import com.copy.account.page.LogScreen
import com.copy.account.page.SecuritySettingsScreen
import com.copy.account.page.SettingsScreen
import com.copy.account.page.UnlockScreen
import com.copy.account.security.AccExportInput
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.SecureVaultStore
import com.copy.account.security.exportAcc
import com.copy.account.security.AccImportResult
import com.copy.account.security.ACCOUNTBOX_MAGIC
import com.copy.account.security.exportAccountBoxJson
import com.copy.account.security.importAccountBoxJson
import com.copy.account.security.importAcc
import com.copy.account.ui.theme.parseThemeJson
import java.io.File
import android.os.Environment
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
    // 通行密钥注册表：设备本地文件；版本号驱动设置页列表重组（删除后自增）。
    val passkeyRegistry = remember { PasskeyRegistry(context) }
    var passkeyRegistryVersion by remember { mutableStateOf(0) }
    // 扫码导入账号：ScanQrOverlay 全屏层开关（首页入口）；扫到 accountbox-import:// 走编辑页预填。
    var scanImportOpen by remember { mutableStateOf(false) }
    /** 主页速览面板展开的账号 id；内存级（rememberSaveable 扛旋转/进程重建），锁屏重解锁后保持展开。 */
    var previewAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    /** 长按“作为模板新建”时临时携带的模板；进入编辑页后预填但按新账号保存。 */
    var editTemplate by remember { mutableStateOf<Account?>(null) }

    // ===== 设置域（P1 抽取）：状态与 DataStore 读写全部收敛在 SettingsContainer =====
    // onThemeApplied：设置生效值变化时把 4 个窗口级主题项回推 MainActivity（原冷启动/reload 的四次回调合一）。
    // 顺手喂平台状态单例：扩展经桥 state 管道拿同款外观（主题随本体，扩展零配置存储）。
    val settingsContainer = rememberSettingsContainer(
        onThemeApplied = { applied ->
            onThemeModeChange(applied.themeMode)
            onAccentThemeChange(applied.accentTheme)
            onCustomThemeJsonChange(applied.customThemeJson)
            onAllowScreenshotsChange(applied.allowScreenshots)
            ExtensionWiring.pushTheme(applied.themeMode, applied.accentTheme, applied.customThemeJson)
        }
    )
    val settings = settingsContainer.settings
    val maskChar = settingsContainer.maskChar

    // 解锁会话域（P3 抽取）：DEK/账号数据/生物识别收敛在 UnlockSession。
    // returnPage：锁定时记住当前页，解锁后恢复（用户在云同步页被锁，回来还回云同步页，
    // 不再粗暴弹回主页）；仅当锁定前真的解锁过才有意义（AppPage.Unlock 本身无恢复价值）。
    var returnPage by remember { mutableStateOf<AppPage>(AppPage.Home) }
    val session = rememberUnlockSession(
        store = store,
        biometricEnabled = { settingsContainer.settings.biometricEnabled },
        // autofillEnabled：开关由设置域（autofillEnabled）落地后接入；先以 false 桥闸常闭，功能不激活。
        autofillEnabled = { settingsContainer.settings.autofillEnabled },
        onBiometricEnabledChange = { enabled -> settingsContainer.update { it.copy(biometricEnabled = enabled) } },
        // 切后台锁定延迟（现读现用，仿 biometricEnabled 模式）：onAppStop 调度 / onAppResume 取消。
        backgroundLockSeconds = { settingsContainer.settings.backgroundLockSeconds },
        // KDF 迭代数目标值（现读现用）：密码解锁时透传给 store，库存值不等即透明升级。
        kdfIterations = { settingsContainer.settings.kdfIterations },
        // 解锁后回跳扩展页：扩展锁定时跳本体解锁前置的一次性标记（consumeResumeExtension
        // 取走即清、2 分钟窗口）。有效 → 借出会话并直接落回扩展页（launchExtensionSettings
        // 内含 borrow）；无标记/过期/无扩展 → 维持 returnPage 原语义（解锁后回锁定前页面）。
        onUnlocked = {
            page = returnPage
            if (ExtensionWiring.consumeResumeAndLaunch(context)) {
                // 消费成功：借出会话并落回扩展页（launchExtensionSettings 内含 borrow，已在 Wiring 内执行）
            }
        },
        onLocked = {
            editTemplate = null
            if (page != AppPage.Unlock) returnPage = page // Unlock 本身不记（否则解锁永远回 Unlock）
            page = AppPage.Unlock
        }
    )
    val accounts = session.accounts
    val groups = session.groups
    val selectedGroupId = session.selectedGroupId
    val dataKey = session.dataKey
    val passwordConfigured = session.passwordConfigured

    // ===== 备份目录/授权域（P1 抽取）：路径、授权、提示状态收敛在 BackupLocationState =====
    val backupLocation = rememberBackupLocationState()
    val directBackup = backupLocation.directBackup

    // 平台状态广播的 context 注入（一次性；组合期主线程安全）。
    // 主体在进程早期可能由 provider 先创建——那也无妨，广播发送点都在 UI 事件之后。
    remember(context) { ExtensionWiring.attachNotifyContext(context.applicationContext) }

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

    /** 改密成功后须重缓存导出密钥（改密会废弃旧缓存）；数据本体不动（DEK 不变）。
     *  改密同时把 KDF 迭代数对齐到当前设置目标值（重包装零额外输密码，顺手升参）。 */
    suspend fun changeMasterPassword(newPassword: String): Result<Unit> {
        val key = session.dataKey ?: return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
        val target = settingsContainer.settings.kdfIterations
        return withContext(Dispatchers.Default) {
            store.changeMasterPassword(newPassword, key, target).onSuccess { material ->
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
            AppPage.SecuritySettings, AppPage.AppearanceSettings, AppPage.About, AppPage.BackupFiles,
            AppPage.Logs -> AppPage.Settings
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
                    // 会话让渡归还：从扩展页回到本体（扩展页 finish/退后台）——标志清零。
                    // 锁定本身由扩展侧 ON_STOP 回锁负责，这里只清让渡态。
                    ExtensionWiring.onSessionEnd()
                    // 后台延迟锁取消：X 秒内回前台 = 无缝继续（超时场景锁已触发，此处无操作）。
                    session.onAppResume()
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
        autoLockSeconds = settings.autoLockSeconds,
        // 空闲锁定只锁 UI 会话，不再撤桥（窗口由用户手动管理）。
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

    // ===== 备份导出接线（供设置页一键备份与备份页共用）=====
    // 接口实现对象 remember 一次，内部经 current* 现读状态，防闭包陈旧（同 IdleLock 的教训）。
    // 提升到 when(page) 之前：AppPage.Settings 与 AppPage.BackupFiles 两个分支都要用它。
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

    /** 指针文件幂等重写（扩展目录继承唯一写入点）；SAF 轨不写（扩展不支持，口径一致）。 */
    fun writeSyncPointer() {
        if (!directBackup) return
        com.copy.account.runtime.writeSyncPointer(context, backupLocation.backupFolder)
    }

    /**
     * 导出防重入锁：双发写盘问题的收口。页面级 busy/冷却只防本页连点，
     * 防不了系统层把一次触摸投递成两次点击（OEM 幽灵触摸）、确认框按钮双击进事件队列、
     * 以及设置页/备份页跨页并发——加密/明文两个导出入口共用本实例，原子锁保证
     * 同一时刻最多一个导出在飞，抢不到锁的第二发直接回失败，不碰写盘。
     *
     * 刻意是**文件级单例**而非 remember：remember 挂在组合上，导出中途旋转/重建
     * Activity 会换一把新锁（旧导出还在飞、新锁却是开的，防重入失效）。
     * 本 App 单 Activity 单组合实例，文件级 ≈ 进程级。
     * 取消语义：导出体是纯阻塞代码（无挂起点），协程被取消时工作照常跑完、
     * finally 必释放锁——旧导出完整落一个文件，期间新导出请求被锁挡下。
     */
    val exportInFlight = remember { ExportInFlight }

    val exporter = remember {
        object : BackupExporter {
            override fun exportEncrypted(onDone: (Result<String>) -> Unit) {
                if (!exportInFlight.compareAndSet(false, true)) {
                    onDone(Result.failure(IllegalStateException("已有导出在进行，请稍候")))
                    return
                }
                scope.launch {
                    try {
                        val result: Result<String> = withContext(Dispatchers.Default) {
                            val gateError = exportGateError()
                            if (gateError != null) {
                                Result.failure<String>(IllegalStateException(gateError))
                            } else {
                                val vaultKey = currentDataKey!!.copyOf()
                                // runCatching 的值不接（导出名在内部 appendLog/返回），.also 只做密钥清零副作用。
                                runCatching {
                                    val material = store.loadCachedBackupKey(vaultKey)
                                        ?: throw ExportPasswordRequiredException()
                                    try {
                                        // WebDAV 配置随库备份（换机免重输）：托管区有记录才带；无 = 旧字段缺省 null。
                                        val webdavRecord = com.copy.account.runtime.WebDavConfigVault.loadForExport(context)
                                        val bytes = exportAcc(
                                            AccExportInput(
                                                PersistedVault(
                                                    accounts = currentAccounts,
                                                    groups = currentGroups,
                                                    selectedGroupId = currentSelectedGroupId,
                                                    webdavConfig = webdavRecord?.let {
                                                        com.copy.account.data.model.WebDavConfigBackup(it.url, it.user, it.passwordEnc)
                                                    }
                                                ),
                                                currentSettings
                                            ), material.key, material.salt, material.iterations
                                        )
                                        try {
                                            // 加密备份命名现读文件名标签：空 = 裸时间戳（时间戳格式 v57 起收紧）。
                                            val name = writeExportBytes(bytes, { uniqueBackupName(backupLocation.backupNameTag, it) }, "application/octet-stream")
                                            // 指针文件幂等重写（设计稿 §2 活性保证）：导出成功后刷新，扩展每次同步现读即跟随
                                            writeSyncPointer()
                                            appendLog(context, LogEvents.EXPORT, "导出备份成功（${name}）")
                                            // 导出后自动上传（fire-and-forget）：拉起扩展跳板 ↑ 上传，
                                            // 结果经扩展 appendLog 落操作日志（成败皆一条「扩展事件」）。
                                            // 无扩展在装/拉起失败 = 静默跳过（导出主流程不受影响）。
                                            com.copy.account.runtime.kickExtensionSync(context)
                                            name
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
                    } finally {
                        exportInFlight.set(false)
                    }
                }
            }

            override fun exportPlaintext(onDone: (Result<String>) -> Unit) {
                if (!exportInFlight.compareAndSet(false, true)) {
                    onDone(Result.failure(IllegalStateException("已有导出在进行，请稍候")))
                    return
                }
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.Default) {
                            // 门禁与加密导出共用 exportGateError()。明文导出不需要 KEK：账号明文此刻就在
                            // accounts/groups 里，所以不碰 loadCachedBackupKey，也就不会触发
                            // ExportPasswordRequiredException 那个「首次导出输密码」的分支。
                            val gateError = exportGateError()
                            if (gateError != null) {
                                Result.failure(IllegalStateException(gateError))
                            } else {
                                runCatching {
                                    // 明文轨与「账号本子」App 互导：产 ACCOUNTBOX v13 格式
                                    //（首行魔数 + JSON）。绑定关系（url/包名）无对应概念，不导出。
                                    val bytes = exportAccountBoxJson(
                                        PersistedVault(
                                            accounts = currentAccounts,
                                            groups = currentGroups,
                                            selectedGroupId = currentSelectedGroupId
                                        ),
                                        System.currentTimeMillis()
                                    )
                                    // 命名与 mime 都与 .acc 分开：文件名带 plain 前缀。写完立刻清零字节，
                                    // 尽量缩短明文在堆上的时间。
                                    // 落点刻意与 .acc 分轨：明文固定写系统「下载」目录；SAF 轨（API<30）
                                    // 系统限制写不了公共 Downloads，仍写授权目录（两台真机均 API>=30，
                                    // 不触达此分支）。命名刻意不走文件名标签：明文轨格式固定不动。
                                    try {
                                        if (directBackup) writeFileBackup(bytes, "Download", ::uniquePlainExportName)
                                        else writeExportBytes(bytes, ::uniquePlainExportName, "application/json")
                                    } finally {
                                        bytes.fill(0)
                                    }
                                }
                            }
                        }
                        onDone(result)
                    } finally {
                        exportInFlight.set(false)
                    }
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

    when (val current = page) {
        AppPage.Unlock -> UnlockScreen(
            firstUse = !passwordConfigured,
            biometricEnabled = settings.biometricEnabled && store.biometricAvailable(),
            resetKey = session.lockGeneration,
            onBiometricUnlock = { session.authenticateBiometric() },
            // PBKDF2 派生 + 文件读写 + 导出密钥缓存在 UnlockSession.unlockWithPassword 内后台线程完成。
            onUnlock = { password -> session.unlockWithPassword(password, settingsContainer.settings.kdfIterations) }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            passkeyAccountIds = remember(passkeyRegistryVersion, accounts) {
                passkeyRegistry.load().mapNotNull { e -> e.accountId.takeIf { it.isNotBlank() } }.toSet()
            },
            onScanImport = { scanImportOpen = true },
            previewAccountId = previewAccountId,
            onPreviewAccountIdChange = { previewAccountId = it },
            onGroupSelected = { session.selectedGroupId = it; session.persistVault() },
            onNewAccount = { editTemplate = null; page = AppPage.Edit(null) },
            onEditAccount = { editTemplate = null; page = AppPage.Edit(it) },
            onTemplateNew = { editTemplate = it; page = AppPage.Edit(null) },
            onDeleteAccount = { id ->
                val name = accounts.firstOrNull { it.id == id }?.name ?: id
                session.accounts = session.accounts.filterNot { it.id == id }
                session.persistVault()
                appendLog(context, LogEvents.ACCOUNT_DELETE, "删除账号「$name」")
            },
            onDeleteAccounts = { ids ->
                val names = accounts.filter { it.id in ids }.joinToString("、") { it.name }
                session.accounts = session.accounts.filterNot { it.id in ids }
                session.persistVault()
                appendLog(context, LogEvents.ACCOUNT_DELETE, "删除 ${ids.size} 个账号（$names）")
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
            quickBackup = exporter,
            onOpenSecurity = { page = AppPage.SecuritySettings },
            onOpenAppearance = { page = AppPage.AppearanceSettings },
            onOpenBackup = { page = AppPage.BackupFiles },
            // 扩展入口的拉起 Intent：进设置页时现查（不跨页缓存，装/卸扩展后回来即新值）。
            // 顺手缓存扩展包名：状态变化定向广播的收件人（隐式广播被 ColorOS 丢弃，必须定向）。
            extensionLaunch = remember(context) {
                ExtensionWiring.resolveLaunchIntent(context).also { intent ->
                    ExtensionWiring.setExtensionPackage(intent?.`package`
                        ?: intent?.component?.packageName)
                }
            },
            onOpenLogs = { page = AppPage.Logs },
            onOpenAbout = { page = AppPage.About },
            onBack = { page = AppPage.Home }
        )


        AppPage.Logs -> LogScreen(onBack = { page = AppPage.Settings })

        AppPage.SecuritySettings -> SecuritySettingsScreen(
            biometricEnabled = settings.biometricEnabled,
            biometricAvailable = store.biometricAvailable(),
            onToggleBiometric = session::configureBiometric,
            onChangeMasterPassword = ::changeMasterPassword,
            autofillEnabled = settings.autofillEnabled,
            // 窗口状态由 AccountApp 读桥后经 props 下发（页面保持无 runtime 依赖，数据流单向）。
            autofillWindowOpen = settings.autofillEnabled && AutofillBridge.active,
            onAutofillEnabledChange = { enabled ->
                settingsContainer.update { it.copy(autofillEnabled = enabled) }
                // 关开关=关闭填充窗口：不再等下次解锁，明文快照、DEK 副本与公开索引必须随开关即时失效。
                if (!enabled) AutofillBridge.clear(context)
            },
            // 手动关闭填充窗口：唯一主动撤桥入口，连公开索引一起清（AutofillBridge.clear）。
            onCloseAutofillWindow = { AutofillBridge.clear(context) },
            // 手动开启填充窗口（会话型开关的开启方向）：开关总闸开着才可达（页面已 gate），此处灌桥。
            onOpenAutofillWindow = {
                if (session.isUnlocked) {
                    AutofillBridge.enable(context, AutofillBridge.toAutofillAccounts(accounts), session.dataKey!!)
                }
            },
            autoLockSeconds = settings.autoLockSeconds,
            onAutoLockChange = { seconds ->
                settingsContainer.update { it.copy(autoLockSeconds = seconds.coerceIn(0, MAX_AUTO_LOCK_SECONDS)) }
            },
            backgroundLockSeconds = settings.backgroundLockSeconds,
            onBackgroundLockChange = { seconds ->
                settingsContainer.update { it.copy(backgroundLockSeconds = seconds.coerceIn(0, 300)) }
            },
            onBack = { page = AppPage.Settings },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onClipboardClearChange = { seconds ->
                settingsContainer.update { it.copy(clipboardClearSeconds = seconds.coerceIn(0, 86_400)) }
            },
            kdfIterations = settings.kdfIterations,
            onKdfIterationsChange = { iterations ->
                settingsContainer.update { it.copy(kdfIterations = iterations.coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)) }
            },
            blockScreenshots = !settings.allowScreenshots,
            onBlockScreenshotsChange = { blocked ->
                val allow = !blocked
                settingsContainer.update { it.copy(allowScreenshots = allow) }
                onAllowScreenshotsChange(allow)
            },
            passkeyEntries = remember(passkeyRegistryVersion) {
                passkeyRegistry.load().map { entry ->
                    PasskeyDisplayEntry(
                        credentialId = entry.credentialId,
                        rpId = entry.rpId,
                        accountName = accounts.firstOrNull { it.id == entry.accountId }?.name.orEmpty(),
                        createdAt = entry.createdAt
                    )
                }
            },
            onDeletePasskey = { credentialId ->
                // 双删：vault 账号段内的 passkey（含私钥）+ 注册表索引条目；
                // 版本号自增触发上方 remember 重读列表。（UI 解锁态：经 session DEK 直写）
                scope.launch {
                    val deleted = session.deletePasskey(credentialId)
                    passkeyRegistry.remove(credentialId)
                    if (deleted) passkeyRegistryVersion++
                }
            },
            passkeySupported = Build.VERSION.SDK_INT >= 34
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
            // exporter 与门禁已提升到 when(page) 之前（设置页一键备份共用同一实例）。

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

            val directoryOps = remember {
                object : BackupDirectoryOps {
                    override val directBackup get() = backupLocation.directBackup
                    override val storageAccessGranted get() = backupLocation.storageAccessGranted
                    override val backupTreeUri get() = backupLocation.backupTreeUri
                    override val backupLocation get() = backupLocation.backupLocation
                    override val backupFolder get() = backupLocation.backupFolder
                    override val backupNameTag get() = backupLocation.backupNameTag
                    override val directoryMessage get() = backupLocation.backupDirectoryMessage
                    override val directoryExpanded get() = backupLocation.directoryExpanded
                    override fun toggleDirectoryExpanded() = backupLocation.toggleDirectoryExpanded()
                    override fun changeBackupNameTag(raw: String) = backupLocation.changeBackupNameTag(raw)
                    override fun changeBackupLocation(raw: String): Boolean {
                        val changed = backupLocation.changeBackupLocation(raw)
                        // 目录跟随（设计稿 §2）：位置变更成功后重写指针文件，扩展下一次同步即跟随
                        if (changed) writeSyncPointer()
                        return changed
                    }
                    override fun changeBackupFolder(raw: String): Boolean {
                        val changed = backupLocation.changeBackupFolder(raw)
                        if (changed) writeSyncPointer()
                        return changed
                    }
                    override fun chooseDirectory() = requestBackupDirectory()
                    override fun requestStorageAccess() { backupLocation.requestStorageAccess() }
                }
            }

            // 同步功能在可选扩展模块（本地放置才参与构建）：备份页保持纯备份页。

                BackupScreen(
                fileStore = fileStore,
                exporter = exporter,
                directory = directoryOps,
                accountCount = accounts.size,
                groupCount = groups.size,
                onImport = { bytes, password ->
                    // 按首行魔数嗅探：账号本子明文格式免密导入；否则按 .acc 解密验证。
                    val firstLine = bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim()
                    if (firstLine == ACCOUNTBOX_MAGIC) {
                        val imported = importAccountBoxJson(bytes)
                        if (imported.isSuccess) {
                            Result.success(AccImportResult(vault = imported.getOrThrow(), settings = settingsContainer.baseSettings))
                        } else {
                            Result.failure(imported.exceptionOrNull() ?: IllegalStateException("账号本子文件解析失败"))
                        }
                    } else {
                        importAcc(bytes, password)
                    }
                },
                onApplyImport = { imported ->
                    session.accounts = imported.vault.accounts
                    session.groups = imported.vault.groups.ifEmpty { initialGroups }
                    session.selectedGroupId = imported.vault.selectedGroupId.ifBlank { "default" }
                    // 通行密钥随库恢复：registry 不进备份文件，从导入的账号数据重建出卡索引
                    //（vault 的 Account.passkeys 是唯一权威源；锁定态出卡的 Service 靠 registry 查询）
                    passkeyRegistry.rebuildFromVault(imported.vault.accounts)
                    passkeyRegistryVersion++
                    // WebDAV 配置随库恢复：写回托管区 + 置待取标记，扩展下次启动经桥取回（免重输）。
                    imported.vault.webdavConfig?.let {
                        com.copy.account.runtime.WebDavConfigVault.storeFromImport(
                            context, com.copy.account.runtime.WebDavConfigRecord(it.url, it.user, it.passwordEnc)
                        )
                    }
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
            onHotpAdvance = session::advanceHotp,
            hasPasskey = remember(passkeyRegistryVersion) {
                passkeyRegistry.byAccountId(current.accountId).isNotEmpty()
            }
        )

        is AppPage.Edit -> AccountEditScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            template = editTemplate,
            groups = groups,
            initialGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            passkeyInfo = remember(passkeyRegistryVersion, current.accountId) {
                passkeyRegistry.byAccountId(current.accountId.orEmpty()).firstOrNull()?.let { entry ->
                    PasskeyDisplayEntry(
                        credentialId = entry.credentialId,
                        rpId = entry.rpId,
                        accountName = "",
                        createdAt = entry.createdAt
                    )
                }
            },
            onDeletePasskey = { credentialId ->
                scope.launch {
                    val deleted = session.deletePasskey(credentialId)
                    passkeyRegistry.remove(credentialId)
                    if (deleted) passkeyRegistryVersion++
                }
            },
            onBack = { page = AppPage.Home; editTemplate = null },
            onCreateGroup = ::createGroup,
            onSave = { edited ->
                val isNew = !accounts.any { it.id == edited.id }
                val nextAccounts = if (!isNew) {
                    accounts.map { if (it.id == edited.id) edited else it }
                } else {
                    accounts + edited
                }
                session.saveVault(nextAccounts = nextAccounts).onSuccess {
                    session.accounts = nextAccounts
                    if (isNew) appendLog(context, LogEvents.ACCOUNT_ADD, "新增账号「${edited.name}」")
                    editTemplate = null
                    page = AppPage.Home
                }
            },
            onDelete = current.accountId?.let { id ->
                {
                    val name = accounts.firstOrNull { it.id == id }?.name ?: id
                    val nextAccounts = accounts.filterNot { it.id == id }
                    session.saveVault(nextAccounts = nextAccounts).onSuccess {
                        session.accounts = nextAccounts
                        appendLog(context, LogEvents.ACCOUNT_DELETE, "删除账号「$name」")
                        page = AppPage.Home
                    }
                }
            }
        )
    }

    // 扫码导入账号（首页入口）：全屏扫码层盖在当前页上；扫到 accountbox-import:// payload
    // 解析成功 → 以 template 进编辑页预览确认（走现有新建保存链路）；非本 App 格式提示无效。
    if (scanImportOpen) {
        com.copy.account.ui.components.ScanQrOverlay(
            onClose = { scanImportOpen = false },
            onScanned = { raw ->
                scanImportOpen = false
                val payload = parseImportPayload(raw)
                if (payload != null) {
                    // 走模板新建：编辑页以 template 预填，保存时自动获得 uid
                    editTemplate = Account(
                        id = "",
                        name = payload.name,
                        username = payload.username,
                        password = payload.password,
                        url = payload.url,
                        customFields = if (payload.notes.isNotBlank()) {
                            listOf(AccountField(id = "import-note", label = "备注", value = payload.notes, hidden = false))
                        } else emptyList()
                    )
                    page = AppPage.Edit(null)
                } else {
                    android.widget.Toast.makeText(context, "二维码不是账号本子导入格式", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * 导出防重入锁的进程级存储：文件级单例（顶层 val，类加载即初始化，进程存活期常驻）。
 * AccountApp 的 remember 只是把这把锁接进组合——旋转/重组换组合实例，锁不换。
 */
private val ExportInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
