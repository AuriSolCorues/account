package com.copy.account

import android.content.Context
import android.net.Uri
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
import com.copy.account.core.crypto.AccExportInput
import com.copy.account.core.crypto.exportAcc
import com.copy.account.core.crypto.importAcc
import com.copy.account.core.storage.SecureVaultStore
import com.copy.account.core.storage.decodeSavedThemes
import com.copy.account.core.storage.encodeSavedThemes
import com.copy.account.core.storage.settingsDataStore
import com.copy.account.core.storage.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.core.storage.ACCENT_THEME_SETTING
import com.copy.account.core.storage.AUTO_LOCK_SETTING
import com.copy.account.core.storage.BACKUP_TREE_URI_SETTING
import com.copy.account.core.storage.BIOMETRIC_SETTING
import com.copy.account.core.storage.CLIPBOARD_CLEAR_SETTING
import com.copy.account.core.storage.CUSTOM_THEME_JSON_SETTING
import com.copy.account.core.storage.CUSTOM_THEMES_SETTING
import com.copy.account.core.storage.LANGUAGE_TAG_SETTING
import com.copy.account.core.storage.THEME_MODE_SETTING
import com.copy.account.data.backup.deleteBackupFile
import com.copy.account.data.backup.initializeBackupDirectory
import com.copy.account.data.backup.readSelectedDocument
import com.copy.account.data.backup.writeBackupFile
import com.copy.account.data.model.Account
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.feature.accounts.HomeScreen
import com.copy.account.feature.backup.BackupScreen
import com.copy.account.feature.detail.AccountDetailScreen
import com.copy.account.feature.edit.AccountEditScreen
import com.copy.account.feature.groups.GroupManageScreen
import com.copy.account.feature.settings.SettingsScreen
import com.copy.account.feature.unlock.UnlockScreen
import com.copy.account.ui.theme.SavedTheme
import com.copy.account.ui.theme.parseThemeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface AppPage {
    data object Unlock : AppPage
    data object Home : AppPage
    data object Groups : AppPage
    data object Settings : AppPage
    data object BackupFiles : AppPage
    data class Detail(val accountId: String) : AppPage
    data class Edit(val accountId: String?) : AppPage
}

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
    val store = remember { SecureVaultStore(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<AppPage>(AppPage.Unlock) }
    var selectedGroupId by remember { mutableStateOf("default") }
    /** 长按“作为模板新建”时临时携带的模板；进入编辑页后预填但按新账号保存。 */
    var editTemplate by remember { mutableStateOf<Account?>(null) }
    var groups by remember { mutableStateOf(initialGroups) }
    var accounts by remember { mutableStateOf(emptyList<Account>()) }
    var dataKey by remember { mutableStateOf<ByteArray?>(null) }
    var lockGeneration by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(AppSettings(customThemeJson = customThemeJson)) }
    var passwordConfigured by remember { mutableStateOf(store.hasMasterPassword()) }
    var biometricPromptActive by remember { mutableStateOf(false) }
    var backupTreeUri by remember { mutableStateOf<String?>(null) }
    var backupDirectoryMessage by remember { mutableStateOf("") }
    /** 当前是否处于前台 RESUME 状态，用于在回到前台时触发一次生物识别。 */
    var resumed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val values = context.settingsDataStore.data.first()
        settings = AppSettings(
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
        backupTreeUri = values[BACKUP_TREE_URI_SETTING]
        onThemeModeChange(settings.themeMode)
        onAccentThemeChange(settings.accentTheme)
        onCustomThemeJsonChange(settings.customThemeJson)
        onAllowScreenshotsChange(settings.allowScreenshots)
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

    val chooseBackupDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            backupDirectoryMessage = "未选择目录"
        } else {
            val result = initializeBackupDirectory(context, uri)
            if (result.isSuccess) {
                backupTreeUri = uri.toString()
                persistBackupTreeUri(backupTreeUri)
                backupDirectoryMessage = "已授权，备份保存于 backups/account"
            } else {
                backupDirectoryMessage = result.exceptionOrNull()?.message ?: "目录不可写，请重新选择"
            }
        }
    }

    fun requestBackupDirectory() {
        backupDirectoryMessage = ""
        chooseBackupDirectory.launch(null)
    }

    fun persistVault() {
        val key = dataKey ?: return
        runCatching { store.save(PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId), key) }
    }

    fun finishUnlock(key: ByteArray, state: PersistedVault) {
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
                if (key != null && state != null) finishUnlock(key, state) else onError()
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
            settings = settings.copy(biometricEnabled = false)
            persistSettings(settings)
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
                        settings = settings.copy(biometricEnabled = true)
                        persistSettings(settings)
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

    BackHandler(enabled = page != AppPage.Unlock && page != AppPage.Home) {
        page = when (page) {
            AppPage.Settings, AppPage.Groups, is AppPage.Detail, is AppPage.Edit -> AppPage.Home
            AppPage.BackupFiles -> AppPage.Settings
            else -> page
        }
    }

    // 统一生命周期：进入后台立即锁定；回到前台且仍处于解锁页时触发一次生物识别。
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
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
                        val key = store.createInitial(password, initialState)
                        key to initialState
                    } else {
                        store.unlockWithPassword(password)
                    }
                }
                if (unlocked != null) {
                    if (firstRun) passwordConfigured = true
                    finishUnlock(unlocked.first, unlocked.second)
                    true
                } else false
            }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onGroupSelected = { selectedGroupId = it; persistVault() },
            onNewAccount = { editTemplate = null; page = AppPage.Edit(null) },
            onEditAccount = { editTemplate = null; page = AppPage.Edit(it) },
            onTemplateNew = { editTemplate = it; page = AppPage.Edit(null) },
            onDeleteAccount = { id ->
                accounts = accounts.filterNot { it.id == id }
                persistVault()
            },
            onManageGroups = { page = AppPage.Groups },
            onOpenSettings = { page = AppPage.Settings },
            onOpenDetail = { page = AppPage.Detail(it) }
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
            biometricEnabled = settings.biometricEnabled,
            biometricAvailable = store.biometricAvailable(),
            onToggleBiometric = ::configureBiometric,
            onChangeMasterPassword = { newPassword ->
                dataKey?.let { store.changeMasterPassword(newPassword, it) }
                    ?: Result.failure(IllegalStateException("当前未解锁，请重新解锁后重试"))
            },
            autoLockMinutes = settings.autoLockMinutes,
            onAutoLockChange = { minutes ->
                settings = settings.copy(autoLockMinutes = minutes.coerceAtLeast(1))
                persistSettings(settings)
            },
            themeMode = settings.themeMode,
            onThemeModeChange = { mode ->
                val normalized = mode.lowercase().let { if (it == "light" || it == "system") it else "dark" }
                settings = settings.copy(themeMode = normalized)
                persistSettings(settings)
                onThemeModeChange(normalized)
            },
            accentTheme = settings.accentTheme,
            onAccentThemeChange = { accent ->
                val normalized = if (accent == "blue") "blue" else "green"
                // 选择内置配色时退出 JSON 自定义主题，避免两个色板同时生效。
                settings = settings.copy(accentTheme = normalized, customThemeJson = "")
                persistSettings(settings)
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
                    settings = settings.copy(customThemeJson = json.trim(), themeMode = mode)
                    persistSettings(settings)
                    onCustomThemeJsonChange(settings.customThemeJson)
                    onThemeModeChange(mode)
                    true
                }
            },
            onSaveCustomTheme = { name, json ->
                val parsed = parseThemeJson(json)
                if (parsed != null) {
                    val saved = SavedTheme("custom-${System.currentTimeMillis()}", name.ifBlank { parsed.name }, json.trim())
                    settings = settings.copy(customThemes = (settings.customThemes + saved).distinctBy { it.id })
                    persistSettings(settings)
                }
            },
            onDeleteCustomTheme = { id ->
                settings = settings.copy(customThemes = settings.customThemes.filterNot { it.id == id })
                persistSettings(settings)
            },
            onBack = { page = AppPage.Home },
            languageTag = settings.languageTag,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onClipboardClearChange = { seconds ->
                settings = settings.copy(clipboardClearSeconds = seconds.coerceIn(0, 86_400))
                persistSettings(settings)
            },
            allowScreenshots = settings.allowScreenshots,
            onAllowScreenshotsChange = { enabled ->
                settings = settings.copy(allowScreenshots = enabled)
                persistSettings(settings)
                onAllowScreenshotsChange(enabled)
                // OPPO/ColorOS 清除 FLAG_SECURE 后需重建窗口才能立即生效，仅在开启截图时重建一次
                if (enabled) activity?.recreate()
            },
            onOpenBackup = { page = AppPage.BackupFiles }
        )

        AppPage.BackupFiles -> BackupScreen(
            onBack = { page = AppPage.Settings },
            backupTreeUri = backupTreeUri,
            directoryMessage = backupDirectoryMessage,
            onChooseDirectory = ::requestBackupDirectory,
            onExportBackup = {
                val tree = backupTreeUri?.let(Uri::parse)
                val material = store.masterKeyMaterial()
                if (tree == null) {
                    Result.failure(IllegalStateException("请先授权备份目录"))
                } else if (material == null) {
                    Result.failure(IllegalStateException("未找到主密码密钥，请重新解锁"))
                } else {
                    val (key, salt, iterations) = material
                    runCatching {
                        val bytes = exportAcc(
                            AccExportInput(
                                PersistedVault(accounts = accounts, groups = groups, selectedGroupId = selectedGroupId),
                                settings
                            ), key, salt, iterations
                        )
                        try {
                            writeBackupFile(context, tree, bytes)
                        } finally {
                            bytes.fill(0)
                        }
                    }.also {
                        key.fill(0)
                        salt.fill(0)
                    }
                }
            },
            onReadBackup = { uri -> readSelectedDocument(context, uri) },
            onDeleteBackup = { uri -> deleteBackupFile(context, uri) },
            onImportBackup = { bytes, password ->
                importAcc(bytes, password)
            },
            onApplyImport = { imported ->
                accounts = imported.vault.accounts
                groups = imported.vault.groups.ifEmpty { initialGroups }
                selectedGroupId = imported.vault.selectedGroupId.ifBlank { "default" }
                settings = imported.settings
                persistVault()
                persistSettings(settings)
                onThemeModeChange(settings.themeMode)
                onAccentThemeChange(settings.accentTheme)
                onCustomThemeJsonChange(settings.customThemeJson)
                onAllowScreenshotsChange(settings.allowScreenshots)
                page = AppPage.Home
            }
        )

        is AppPage.Detail -> AccountDetailScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onBack = { page = AppPage.Home },
            onEdit = { page = AppPage.Edit(current.accountId) }
        )

        is AppPage.Edit -> AccountEditScreen(
            account = accounts.firstOrNull { it.id == current.accountId },
            template = editTemplate,
            groups = groups,
            initialGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            onBack = { page = AppPage.Home; editTemplate = null },
            onCreateGroup = ::createGroup,
            onSave = { edited ->
                accounts = if (accounts.any { it.id == edited.id }) {
                    accounts.map { if (it.id == edited.id) edited else it }
                } else {
                    accounts + edited
                }
                persistVault()
                editTemplate = null
                page = AppPage.Home
            },
            onDelete = current.accountId?.let { id ->
                {
                    accounts = accounts.filterNot { it.id == id }
                    persistVault()
                    page = AppPage.Home
                }
            }
        )
    }
}
