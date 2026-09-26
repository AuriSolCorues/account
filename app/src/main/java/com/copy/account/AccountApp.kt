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

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.copy.account.data.config.ACCENT_THEME_SETTING
import com.copy.account.data.config.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.data.config.AUTO_LOCK_SETTING
import com.copy.account.data.config.AppSettingsOverride
import com.copy.account.data.config.BACKUP_FOLDER_SETTING
import com.copy.account.data.config.BACKUP_TREE_URI_SETTING
import com.copy.account.data.config.BIOMETRIC_SETTING
import com.copy.account.data.config.CLIPBOARD_CLEAR_SETTING
import com.copy.account.data.config.CUSTOM_THEME_JSON_SETTING
import com.copy.account.data.config.CUSTOM_THEMES_SETTING
import com.copy.account.data.config.LANGUAGE_TAG_SETTING
import com.copy.account.data.config.THEME_MODE_SETTING
import com.copy.account.data.config.applyOverride
import com.copy.account.data.config.decodeSavedThemes
import com.copy.account.data.config.encodeSavedThemes
import com.copy.account.data.config.loadAppSettingsOverride
import com.copy.account.data.config.loadSafAppSettingsOverride
import com.copy.account.data.config.settingsDataStore
import com.copy.account.data.backup.BackupNameFactory
import com.copy.account.data.backup.DEFAULT_BACKUP_FOLDER
import com.copy.account.data.backup.deleteBackupFile
import com.copy.account.data.backup.deleteFileBackup
import com.copy.account.data.backup.hasStorageAccess
import com.copy.account.data.backup.initializeBackupDirectory
import com.copy.account.data.backup.normalizeBackupFolder
import com.copy.account.data.backup.plainExportTimestamp
import com.copy.account.data.backup.readFileBackup
import com.copy.account.data.backup.readSelectedDocument
import com.copy.account.data.backup.uniqueBackupName
import com.copy.account.data.backup.uniquePlainExportName
import com.copy.account.data.backup.writeBackupFile
import com.copy.account.data.backup.writeFileBackup
import com.copy.account.data.model.Account
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.navigation.AppPage
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
import com.copy.account.security.VaultUnlock
import com.copy.account.security.exportAcc
import com.copy.account.security.exportPlainJson
import com.copy.account.security.importAcc
import com.copy.account.security.isHotp
import com.copy.account.ui.theme.SavedTheme
import com.copy.account.ui.theme.parseThemeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
    val vaultWriteMutex = remember { Mutex() }
    var page by remember { mutableStateOf<AppPage>(AppPage.Unlock) }
    var selectedGroupId by remember { mutableStateOf("default") }
    /** 长按“作为模板新建”时临时携带的模板；进入编辑页后预填但按新账号保存。 */
    var editTemplate by remember { mutableStateOf<Account?>(null) }
    var groups by remember { mutableStateOf(initialGroups) }
    var accounts by remember { mutableStateOf(emptyList<Account>()) }
    var dataKey by remember { mutableStateOf<ByteArray?>(null) }
    var lockGeneration by remember { mutableIntStateOf(0) }
    /** DataStore 真值（App 正常设置）。appsettings.json 外挂只读覆盖，不写回。 */
    var baseSettings by remember { mutableStateOf(AppSettings(customThemeJson = customThemeJson)) }
    /** 外挂覆盖层：启动为 null（不主动读文件），仅手动「重新加载配置文件」时置入。 */
    var appSettingsOverride by remember { mutableStateOf<AppSettingsOverride?>(null) }
    /** 生效设置 = DataStore 真值 + 外挂覆盖（文件缺失/解析失败时与真值一致）。 */
    val settings = applyOverride(baseSettings, appSettingsOverride)
    /** 生效掩码符号；多字符配置取首字符，空串回退默认圆点。 */
    val maskChar = settings.maskChar.firstOrNull() ?: '•'
    var passwordConfigured by remember { mutableStateOf(store.hasMasterPassword()) }
    var biometricPromptActive by remember { mutableStateOf(false) }
    var backupTreeUri by remember { mutableStateOf<String?>(null) }
    var backupDirectoryMessage by remember { mutableStateOf("") }
    /** 备份子路径（相对内部存储根）；用户可在备份页手填，非法值由 normalizeBackupFolder 挡掉。 */
    var backupFolder by remember { mutableStateOf(DEFAULT_BACKUP_FOLDER) }
    /** API>=30 直写备份（所有文件访问 + 可自定义 内部存储子目录）；API<30 仍走 SAF 目录授权。 */
    val directBackup = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    var storageAccessGranted by remember {
        mutableStateOf(directBackup && hasStorageAccess())
    }
    /** 当前是否处于前台 RESUME 状态，用于在回到前台时触发一次生物识别。 */
    var resumed by remember { mutableStateOf(false) }

    // LaunchedEffect(Unit)：首帧启动、键不变只跑一次的协程——冷启动从 DataStore 读一次设置真值。
    // .data.first() 取 Flow 首值即退出（不持续订阅），之后的变更靠各回调增量更新。
    LaunchedEffect(Unit) {
        val values = context.settingsDataStore.data.first()
        val loadedSettings = AppSettings(
            biometricEnabled = values[BIOMETRIC_SETTING] ?: false,
            autoLockMinutes = (values[AUTO_LOCK_SETTING] ?: 5).coerceAtLeast(1),
            themeMode = values[THEME_MODE_SETTING] ?: themeMode,
            accentTheme = values[ACCENT_THEME_SETTING] ?: accentTheme,
            languageTag = values[LANGUAGE_TAG_SETTING] ?: "zh-CN",
            customThemeJson = values[CUSTOM_THEME_JSON_SETTING] ?: customThemeJson,
            customThemes = decodeSavedThemes(values[CUSTOM_THEMES_SETTING].orEmpty()),
            clipboardClearSeconds = (values[CLIPBOARD_CLEAR_SETTING] ?: 30).coerceIn(0, 86_400),
            allowScreenshots = values[ALLOW_SCREENSHOTS_SETTING] ?: allowScreenshots
        )
        baseSettings = loadedSettings
        backupTreeUri = values[BACKUP_TREE_URI_SETTING]
        // 存的值可能来自旧版本或被手改，兜底再规范化一次：DataStore 是外部输入，视为不可信。
        backupFolder = normalizeBackupFolder(values[BACKUP_FOLDER_SETTING].orEmpty()) ?: DEFAULT_BACKUP_FOLDER
        onThemeModeChange(loadedSettings.themeMode)
        onAccentThemeChange(loadedSettings.accentTheme)
        onCustomThemeJsonChange(loadedSettings.customThemeJson)
        onAllowScreenshotsChange(loadedSettings.allowScreenshots)
    }

    fun persistSettings(value: AppSettings) {
        scope.launch {
            context.settingsDataStore.edit {
                it[BIOMETRIC_SETTING] = value.biometricEnabled
                it[AUTO_LOCK_SETTING] = value.autoLockMinutes
                it[THEME_MODE_SETTING] = value.themeMode
                it[ACCENT_THEME_SETTING] = value.accentTheme
                it[LANGUAGE_TAG_SETTING] = value.languageTag
                it[CUSTOM_THEME_JSON_SETTING] = value.customThemeJson
                it[CUSTOM_THEMES_SETTING] = encodeSavedThemes(value.customThemes)
                it[CLIPBOARD_CLEAR_SETTING] = value.clipboardClearSeconds.coerceIn(0, 86_400)
                it[ALLOW_SCREENSHOTS_SETTING] = value.allowScreenshots
            }
        }
    }

    fun persistBackupTreeUri(uri: String?) {
        scope.launch {
            context.settingsDataStore.edit {
                if (uri == null) it.remove(BACKUP_TREE_URI_SETTING) else it[BACKUP_TREE_URI_SETTING] = uri
            }
        }
    }

    fun persistBackupFolder(folder: String) {
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_FOLDER_SETTING] = folder }
        }
    }

    /** 改备份子路径：normalize 返回 null（绝对路径或含 ..）视为非法，返回 false 供弹窗提示。 */
    fun changeBackupFolder(raw: String): Boolean {
        val normalized = normalizeBackupFolder(raw) ?: return false
        backupFolder = normalized
        persistBackupFolder(normalized)
        return true
    }

    /**
     * 手动「重新加载配置文件」：重读备份目录里的 appsettings.json 并应用到生效值。
     * 文件缺失/无权限/解析失败 → 覆盖层置 null，恢复 DataStore 业务（与旧行为一致，不区分原因）。
     * 走哪轨取决于 directBackup，所以分派留在本文件，不下沉到 AppSettingsStore。
     */
    fun reloadSettings() {
        val override = if (directBackup) {
            loadAppSettingsOverride(File(Environment.getExternalStorageDirectory(), backupFolder))
        } else {
            val tree = backupTreeUri?.let(Uri::parse)
            if (tree == null) null else loadSafAppSettingsOverride(context, tree, backupFolder)
        }
        appSettingsOverride = override
        val effective = applyOverride(baseSettings, override)
        onThemeModeChange(effective.themeMode)
        onAccentThemeChange(effective.accentTheme)
        onCustomThemeJsonChange(effective.customThemeJson)
        onAllowScreenshotsChange(effective.allowScreenshots)
    }

    // 声明式版 startActivityForResult：rememberLauncherForActivityResult 注册「意图+回调」，
    // launch() 发出（这里是 SAF 选目录树），用户操作完在回调里拿结果（content URI）。
    val chooseBackupDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            backupDirectoryMessage = "未选择目录"
        } else {
            val result = initializeBackupDirectory(context, uri, backupFolder)
            if (result.isSuccess) {
                backupTreeUri = uri.toString()
                persistBackupTreeUri(backupTreeUri)
                backupDirectoryMessage = "已授权，备份保存于 $backupFolder"
            } else {
                backupDirectoryMessage = result.exceptionOrNull()?.message ?: "目录不可写，请重新选择"
            }
        }
    }

    fun requestBackupDirectory() {
        backupDirectoryMessage = ""
        chooseBackupDirectory.launch(null)
    }

    /** 跳系统「所有文件访问」授权页；授予后返回，由 ON_RESUME 刷新 storageAccessGranted。 */
    fun requestStorageAccess() {
        if (!directBackup) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
    }

    suspend fun saveVault(
        nextAccounts: List<Account> = accounts,
        nextGroups: List<Group> = groups,
        nextSelectedGroupId: String = selectedGroupId
    ): Result<Unit> {
        val key = dataKey?.copyOf() ?: return Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
        val snapshot = PersistedVault(accounts = nextAccounts, groups = nextGroups, selectedGroupId = nextSelectedGroupId)
        return try {
            vaultWriteMutex.withLock {
                withContext(Dispatchers.IO) { runCatching { store.save(snapshot, key) } }
            }
        } finally {
            key.fill(0)
        }
    }

    fun persistVault() {
        scope.launch { saveVault() }
    }

    fun finishUnlock(unlocked: VaultUnlock) {
        dataKey?.fill(0)
        dataKey = unlocked.dataKey
        accounts = unlocked.state.accounts
        groups = unlocked.state.groups.ifEmpty { initialGroups }
        selectedGroupId = unlocked.state.selectedGroupId.ifBlank { "default" }
        page = AppPage.Home
    }

    fun finishBiometricUnlock(key: ByteArray, state: PersistedVault) {
        dataKey?.fill(0)
        dataKey = key
        accounts = state.accounts
        groups = state.groups.ifEmpty { initialGroups }
        selectedGroupId = state.selectedGroupId.ifBlank { "default" }
        page = AppPage.Home
    }

    fun lockApp() {
        dataKey?.fill(0)
        dataKey = null
        accounts = emptyList()
        editTemplate = null
        lockGeneration++
        page = AppPage.Unlock
    }

    /** HOTP 复制即 +1：把该账号计数器 +1 并持久化，下次重绘即下一组码。 */
    fun advanceHotp(id: String) {
        accounts = accounts.map { account ->
            if (account.id == id && account.isHotp) {
                account.copy(totpCounter = account.totpCounter + 1)
            } else account
        }
        persistVault()
    }

    fun authenticateBiometric(onError: () -> Unit = {}) {
        Log.d("AccountApp", "authenticateBiometric: active=$biometricPromptActive enabled=${settings.biometricEnabled} avail=${store.biometricAvailable()}")
        if (biometricPromptActive) return
        val host = activity as? FragmentActivity ?: return onError()
        if (!settings.biometricEnabled || !store.biometricAvailable()) return onError()
        val cipher = runCatching { store.beginBiometricDecrypt() }.getOrNull() ?: return onError()
        val encryptedDek = store.biometricCiphertext() ?: return onError()
        biometricPromptActive = true
        fun failBiometric() {
            biometricPromptActive = false
            onError()
        }
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val key = runCatching { result.cryptoObject?.cipher?.doFinal(encryptedDek) }.getOrNull()
                val state = key?.let { store.load(it) }
                biometricPromptActive = false
                if (key != null && state != null) finishBiometricUnlock(key, state) else onError()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { failBiometric() }
            override fun onAuthenticationFailed() { }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁账号本子")
                .setSubtitle("使用指纹或面容解锁")
                .setNegativeButtonText("使用主密码")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    fun configureBiometric(enable: Boolean) {
        if (!enable) {
            store.disableBiometric()
            baseSettings = baseSettings.copy(biometricEnabled = false)
            persistSettings(baseSettings)
            return
        }
        val host = activity as? FragmentActivity ?: return
        val key = dataKey ?: return
        if (!store.biometricAvailable()) return
        val cipher = runCatching { store.beginBiometricEncrypt() }.getOrNull() ?: return
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching { store.saveBiometricWrapped(result.cryptoObject?.cipher ?: cipher, key) }
                    .onSuccess {
                        baseSettings = baseSettings.copy(biometricEnabled = true)
                        persistSettings(baseSettings)
                    }
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("启用生物识别")
                .setSubtitle("验证指纹或面容以启用快速解锁")
                .setNegativeButtonText("取消")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
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
                    if (directBackup) storageAccessGranted = hasStorageAccess()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    // 切后台时若生物识别弹窗尚未回调，标志位可能卡 true，这里强制复位。
                    biometricPromptActive = false
                }
                Lifecycle.Event.ON_STOP -> if (dataKey != null) lockApp()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // 解锁页 + 已恢复前台 + 已配置生物识别 => 自动弹出指纹；避免后台/前台切换的时序竞争。
    LaunchedEffect(page, resumed, passwordConfigured, settings.biometricEnabled) {
        if (page == AppPage.Unlock && resumed && passwordConfigured && settings.biometricEnabled) {
            delay(300)
            if (page == AppPage.Unlock && resumed) authenticateBiometric()
        }
    }

    fun createGroup(name: String): String {
        val id = "custom-${System.currentTimeMillis()}"
        groups = groups + Group(id, name.trim(), GroupKind.CUSTOM)
        // 立即保存分组，避免用户离开账号编辑页但未保存账号时丢失分组。
        persistVault()
        return id
    }

    when (val current = page) {
        AppPage.Unlock -> UnlockScreen(
            firstUse = !passwordConfigured,
            biometricEnabled = settings.biometricEnabled && store.biometricAvailable(),
            resetKey = lockGeneration,
            onBiometricUnlock = { authenticateBiometric() },
            onUnlock = { password ->
                // PBKDF2 派生 + 文件读写放后台线程，避免阻塞主线程导致解锁卡顿。
                val firstRun = !passwordConfigured
                val unlocked = withContext(Dispatchers.Default) {
                    if (firstRun) {
                        val initialState = PersistedVault(accounts = initialAccounts, groups = initialGroups)
                        store.createInitial(password, initialState)
                    } else {
                        store.unlockWithPassword(password)
                    }
                }
                if (unlocked != null) {
                    // 导出密钥只在这里短暂存在：缓存由 DEK 加密，完成后立刻清零。
                    withContext(Dispatchers.Default) {
                        try {
                            store.cacheBackupKey(unlocked.backupKey, unlocked.dataKey)
                        } finally {
                            unlocked.backupKey.clear()
                        }
                    }
                    if (firstRun) passwordConfigured = true
                    finishUnlock(unlocked)
                    true
                } else false
            }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            onGroupSelected = { selectedGroupId = it; persistVault() },
            onNewAccount = { editTemplate = null; page = AppPage.Edit(null) },
            onEditAccount = { editTemplate = null; page = AppPage.Edit(it) },
            onTemplateNew = { editTemplate = it; page = AppPage.Edit(null) },
            onDeleteAccount = { id ->
                accounts = accounts.filterNot { it.id == id }
                persistVault()
            },
            onMoveAccounts = { ids, fromGroupId, toGroupId ->
                val from = groups.firstOrNull { it.id == fromGroupId }
                val to = groups.firstOrNull { it.id == toGroupId }
                accounts = accounts.map { account ->
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
                persistVault()
            },
            onManageGroups = { page = AppPage.Groups },
            onOpenSettings = { page = AppPage.Settings },
            onOpenDetail = { page = AppPage.Detail(it) },
            onHotpAdvance = ::advanceHotp
        )

        AppPage.Groups -> GroupManageScreen(
            groups = groups,
            onBack = { page = AppPage.Home },
            onAddGroup = { val id = createGroup(it); persistVault(); id },
            onRenameGroup = { id, name -> groups = groups.map { if (it.id == id) it.copy(name = name) else it }; persistVault() },
            onDeleteGroup = { id ->
                groups = groups.filterNot { it.id == id }
                accounts = accounts.map { it.copy(groups = it.groups - id) }
                if (selectedGroupId == id) selectedGroupId = "default"
                persistVault()
            },
            accountCount = { id ->
                when (groups.firstOrNull { it.id == id }?.kind) {
                    GroupKind.DEFAULT -> accounts.count { it.groups.isEmpty() }
                    GroupKind.DYNAMIC -> accounts.count { it.hasTotp }
                    GroupKind.CUSTOM -> accounts.count { id in it.groups }
                    else -> 0
                }
            },
            onMoveCustomGroup = { id, direction ->
                val fixed = groups.take(2)
                val custom = groups.drop(2).toMutableList()
                val index = custom.indexOfFirst { it.id == id }
                val target = index + direction
                if (index >= 0 && target in custom.indices) {
                    val item = custom.removeAt(index)
                    custom.add(target, item)
                    groups = fixed + custom
                    persistVault()
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
            onToggleBiometric = ::configureBiometric,
            onChangeMasterPassword = { newPassword ->
                val result = dataKey?.let { key -> withContext(Dispatchers.Default) {
                    store.changeMasterPassword(newPassword, key).onSuccess { material ->
                        try {
                            store.cacheBackupKey(material, key)
                        } finally {
                            material.clear()
                        }
                    }.map { }
                } }
                    ?: Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
                result
            },
            autoLockMinutes = settings.autoLockMinutes,
            onAutoLockChange = { minutes ->
                baseSettings = baseSettings.copy(autoLockMinutes = minutes.coerceAtLeast(1))
                persistSettings(baseSettings)
            },
            onBack = { page = AppPage.Settings },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onClipboardClearChange = { seconds ->
                baseSettings = baseSettings.copy(clipboardClearSeconds = seconds.coerceIn(0, 86_400))
                persistSettings(baseSettings)
            },
            allowScreenshots = !settings.allowScreenshots,
            onAllowScreenshotsChange = { blocked ->
                val allow = !blocked
                baseSettings = baseSettings.copy(allowScreenshots = allow)
                persistSettings(baseSettings)
                onAllowScreenshotsChange(allow)
            }
        )

        AppPage.AppearanceSettings -> AppearanceSettingsScreen(
            themeMode = settings.themeMode,
            onThemeModeChange = { mode ->
                val normalized = mode.lowercase().let { if (it == "light" || it == "system") it else "dark" }
                baseSettings = baseSettings.copy(themeMode = normalized)
                persistSettings(baseSettings)
                onThemeModeChange(normalized)
            },
            accentTheme = settings.accentTheme,
            onAccentThemeChange = { accent ->
                val normalized = if (accent == "blue") "blue" else "green"
                // 选择内置配色时退出 JSON 自定义主题，避免两个色板同时生效。
                baseSettings = baseSettings.copy(accentTheme = normalized, customThemeJson = "")
                persistSettings(baseSettings)
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
                    baseSettings = baseSettings.copy(customThemeJson = json.trim(), themeMode = mode)
                    persistSettings(baseSettings)
                    onCustomThemeJsonChange(json.trim())
                    onThemeModeChange(mode)
                    true
                }
            },
            onSaveCustomTheme = { name, json ->
                val parsed = parseThemeJson(json)
                if (parsed != null) {
                    val saved = SavedTheme("custom-${System.currentTimeMillis()}", name.ifBlank { parsed.name }, json.trim())
                    baseSettings = baseSettings.copy(customThemes = (settings.customThemes + saved).distinctBy { it.id })
                    persistSettings(baseSettings)
                }
            },
            onDeleteCustomTheme = { id ->
                baseSettings = baseSettings.copy(customThemes = settings.customThemes.filterNot { it.id == id })
                persistSettings(baseSettings)
            },
            onReloadSettings = ::reloadSettings,
            externalConfigLoaded = appSettingsOverride != null,
            onBack = { page = AppPage.Settings }
        )

        AppPage.About -> AboutScreen(onBack = { page = AppPage.Settings })

        AppPage.BackupFiles -> {
            /**
             * 加密导出与明文导出共用的门禁。刻意只写一份：明文路径风险更高（全部密码无加密落盘），
             * 若各维护一份，将来加第 4 条条件时漏改明文那份，就是一次静默的安全绕过。
             */
            fun exportGateError(): String? = when {
                directBackup && !storageAccessGranted -> "请先授予「所有文件访问」权限"
                !directBackup && backupTreeUri == null -> "请先授权备份目录"
                dataKey == null -> "当前未解锁，请重新解锁后重试"
                else -> null
            }

            /** 落盘分派：直写轨只有命名器，SAF 轨还要 MIME。两条轨的差异收在这里。 */
            fun writeExportBytes(bytes: ByteArray, nameFactory: BackupNameFactory, mimeType: String): String {
                val tree = backupTreeUri?.let(Uri::parse)
                return if (directBackup) writeFileBackup(bytes, backupFolder, nameFactory)
                else writeBackupFile(context, tree!!, bytes, backupFolder, nameFactory, mimeType)
            }

                BackupScreen(
                onBack = { page = AppPage.Settings },
                directBackup = directBackup,
                storageAccessGranted = storageAccessGranted,
                backupTreeUri = backupTreeUri,
                backupFolder = backupFolder,
                directoryMessage = backupDirectoryMessage,
                onChangeBackupFolder = ::changeBackupFolder,
                onChooseDirectory = ::requestBackupDirectory,
                onRequestStorageAccess = ::requestStorageAccess,
                onExportBackup = {
                    val gateError = exportGateError()
                    if (gateError != null) {
                        Result.failure(IllegalStateException(gateError))
                    } else {
                        val vaultKey = dataKey!!.copyOf()
                        runCatching {
                            val material = store.loadCachedBackupKey(vaultKey) ?: throw ExportPasswordRequiredException()
                            try {
                                val bytes = exportAcc(
                                    AccExportInput(
                                        PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId),
                                        settings
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
                        }.also {
                            vaultKey.fill(0)
                        }
                    }
                },
                onPrepareExport = { password ->
                    if (dataKey == null) {
                        Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
                    } else {
                        val unlocked = store.unlockWithPassword(password)
                        if (unlocked == null) {
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
                },
                onExportPlaintext = {
                    // 门禁与加密导出共用 exportGateError()。明文导出不需要 KEK：账号明文此刻就在
                    // accounts/groups 里，所以不碰 loadCachedBackupKey，也就不会触发
                    // ExportPasswordRequiredException 那个「首次导出输密码」的分支。
                    val gateError = exportGateError()
                    if (gateError != null) {
                        Result.failure(IllegalStateException(gateError))
                    } else {
                        runCatching {
                            val bytes = exportPlainJson(
                                PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId),
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
                },
                accountCount = accounts.size,
                groupCount = groups.size,
                onReadBackup = { uri -> readSelectedDocument(context, uri) },
                onDeleteBackup = { uri -> deleteBackupFile(context, uri) },
                onReadFileBackup = { file -> readFileBackup(file) },
                onDeleteFileBackup = { file -> deleteFileBackup(file) },
                onImportBackup = { bytes, password ->
                    importAcc(bytes, password)
                },
                onApplyImport = { imported ->
                    accounts = imported.vault.accounts
                    groups = imported.vault.groups.ifEmpty { initialGroups }
                    selectedGroupId = imported.vault.selectedGroupId.ifBlank { "default" }
                    // 生物识别包装密钥属于当前设备，备份不携带它；保留本机开关，使已启用的指纹解锁在导入后仍可用。
                    val importedSettings = imported.settings.copy(biometricEnabled = baseSettings.biometricEnabled)
                    baseSettings = importedSettings
                    persistVault()
                    persistSettings(importedSettings)
                    onThemeModeChange(importedSettings.themeMode)
                    onAccentThemeChange(importedSettings.accentTheme)
                    onCustomThemeJsonChange(importedSettings.customThemeJson)
                    onAllowScreenshotsChange(importedSettings.allowScreenshots)
                    page = AppPage.Home
                }
                )
        }

        is AppPage.Detail -> AccountDetailScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
            onBack = { page = AppPage.Home },
            onEdit = { page = AppPage.Edit(current.accountId) },
            onHotpAdvance = ::advanceHotp
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
                saveVault(nextAccounts = nextAccounts).onSuccess {
                    accounts = nextAccounts
                    editTemplate = null
                    page = AppPage.Home
                }
            },
            onDelete = current.accountId?.let { id ->
                {
                    val nextAccounts = accounts.filterNot { it.id == id }
                    saveVault(nextAccounts = nextAccounts).onSuccess {
                        accounts = nextAccounts
                        page = AppPage.Home
                    }
                }
            }
        )
    }
}
