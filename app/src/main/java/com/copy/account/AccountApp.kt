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
import com.copy.account.security.parseImportPayload
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
    val store = remember { SecureVaultStore(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<AppPage>(AppPage.Unlock) }
    var scanImportOpen by remember { mutableStateOf(false) }
    var previewAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTemplate by remember { mutableStateOf<Account?>(null) }

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

    var returnPage by remember { mutableStateOf<AppPage>(AppPage.Home) }
    val session = rememberUnlockSession(
        store = store,
        biometricEnabled = { settingsContainer.settings.biometricEnabled },
        autofillEnabled = { settingsContainer.settings.autofillEnabled },
        onBiometricEnabledChange = { enabled -> settingsContainer.update { it.copy(biometricEnabled = enabled) } },
        backgroundLockSeconds = { settingsContainer.settings.backgroundLockSeconds },
        kdfIterations = { settingsContainer.settings.kdfIterations },
        onUnlocked = {
            page = returnPage
            if (ExtensionWiring.consumeResumeAndLaunch(context)) {
            }
        },
        onLocked = {
            editTemplate = null
            if (page != AppPage.Unlock) returnPage = page
            page = AppPage.Unlock
        }
    )
    val accounts = session.accounts
    val groups = session.groups
    val selectedGroupId = session.selectedGroupId
    val dataKey = session.dataKey
    val passwordConfigured = session.passwordConfigured

    val backupLocation = rememberBackupLocationState()
    val directBackup = backupLocation.directBackup

    remember(context) { ExtensionWiring.attachNotifyContext(context.applicationContext) }

    var resumed by remember { mutableStateOf(false) }

    val chooseBackupDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        backupLocation.onDirectoryChosen(uri)
    }

    fun requestBackupDirectory() {
        backupLocation.clearMessage()
        chooseBackupDirectory.launch(null)
    }

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

    BackHandler(enabled = page != AppPage.Unlock && page != AppPage.Home) {
        page = when (page) {
            AppPage.Settings, AppPage.Groups, is AppPage.Detail, is AppPage.Edit -> AppPage.Home
            AppPage.SecuritySettings, AppPage.AppearanceSettings, AppPage.About, AppPage.BackupFiles,
            AppPage.Logs -> AppPage.Settings
            else -> page
        }
    }

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    ExtensionWiring.onSessionEnd()
                    session.onAppResume()
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

    IdleLockHost(
        activity = activity,
        isUnlocked = dataKey != null,
        autoLockSeconds = settings.autoLockSeconds,
        onLock = session::lockApp
    )

    LaunchedEffect(page, resumed, passwordConfigured, settings.biometricEnabled) {
        if (page == AppPage.Unlock && resumed && passwordConfigured && settings.biometricEnabled) {
            delay(300)
            if (page == AppPage.Unlock && resumed) session.authenticateBiometric()
        }
    }

    fun createGroup(name: String): String {
        val id = "custom-${System.currentTimeMillis()}"
        session.groups = session.groups + Group(id, name.trim(), GroupKind.CUSTOM)
        session.persistVault()
        return id
    }

    val currentAccounts by rememberUpdatedState(accounts)
    val currentGroups by rememberUpdatedState(groups)
    val currentSelectedGroupId by rememberUpdatedState(selectedGroupId)
    val currentSettings by rememberUpdatedState(settings)
    val currentDataKey by rememberUpdatedState(dataKey)

    fun exportGateError(): String? = when {
        directBackup && !backupLocation.storageAccessGranted -> "请先授予「所有文件访问」权限"
        !directBackup && backupLocation.backupTreeUri == null -> "请先授权备份目录"
        currentDataKey == null -> "当前未解锁，请重新解锁后重试"
        else -> null
    }

    fun writeExportBytes(bytes: ByteArray, nameFactory: BackupNameFactory, mimeType: String): String {
        val tree = backupLocation.backupTreeUri?.let(Uri::parse)
        return if (directBackup) writeFileBackup(bytes, backupLocation.backupFolder, nameFactory)
        else writeBackupFile(context, tree!!, bytes, backupLocation.backupFolder, nameFactory, mimeType)
    }

    fun writeSyncPointer() {
        if (!directBackup) return
        com.copy.account.runtime.writeSyncPointer(context, backupLocation.backupFolder)
    }

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
                                runCatching {
                                    val material = store.loadCachedBackupKey(vaultKey)
                                        ?: throw ExportPasswordRequiredException()
                                    try {
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
                                            val name = writeExportBytes(bytes, { uniqueBackupName(backupLocation.backupNameTag, it) }, "application/octet-stream")
                                            writeSyncPointer()
                                            appendLog(context, LogEvents.EXPORT, "导出备份成功（${name}）")
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
                            val gateError = exportGateError()
                            if (gateError != null) {
                                Result.failure(IllegalStateException(gateError))
                            } else {
                                runCatching {
                                    val bytes = exportAccountBoxJson(
                                        PersistedVault(
                                            accounts = currentAccounts,
                                            groups = currentGroups,
                                            selectedGroupId = currentSelectedGroupId
                                        ),
                                        System.currentTimeMillis()
                                    )
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
            onUnlock = { password -> session.unlockWithPassword(password, settingsContainer.settings.kdfIterations) }
        )

        AppPage.Home -> HomeScreen(
            accounts = accounts,
            groups = groups,
            selectedGroupId = selectedGroupId,
            clipboardClearSeconds = settings.clipboardClearSeconds,
            maskChar = maskChar,
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
            autofillWindowOpen = settings.autofillEnabled && AutofillBridge.active,
            onAutofillEnabledChange = { enabled ->
                settingsContainer.update { it.copy(autofillEnabled = enabled) }
                if (!enabled) AutofillBridge.clear(context)
            },
            onCloseAutofillWindow = { AutofillBridge.clear(context) },
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

                BackupScreen(
                fileStore = fileStore,
                exporter = exporter,
                directory = directoryOps,
                accountCount = accounts.size,
                groupCount = groups.size,
                onImport = { bytes, password ->
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
                    imported.vault.webdavConfig?.let {
                        com.copy.account.runtime.WebDavConfigVault.storeFromImport(
                            context, com.copy.account.runtime.WebDavConfigRecord(it.url, it.user, it.passwordEnc)
                        )
                    }
                    val importedSettings = imported.settings.copy(biometricEnabled = settingsContainer.baseSettings.biometricEnabled)
                    settingsContainer.update { importedSettings }
                    session.persistVault()
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
            groups = groups,
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

    if (scanImportOpen) {
        com.copy.account.ui.components.ScanQrOverlay(
            onClose = { scanImportOpen = false },
            onScanned = { raw ->
                scanImportOpen = false
                val payload = parseImportPayload(raw)
                if (payload != null) {
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

private val ExportInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
