package com.copy.account.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.security.AccImportResult
import com.copy.account.security.ACCOUNTBOX_MAGIC
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.R
import androidx.compose.ui.res.stringResource
import com.copy.account.data.backup.backupLocationDisplay
import com.copy.account.data.backup.normalizeBackupLocation
import com.copy.account.data.backup.normalizeBackupNameTag
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.CollapsibleSection
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.ExportConfirmDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TopBarTextButton
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BackupScreen(
    fileStore: BackupFileStore,
    exporter: BackupExporter,
    directory: BackupDirectoryOps,
    accountCount: Int,
    groupCount: Int,
    onImport: (ByteArray, String) -> Result<AccImportResult>?,
    onApplyImport: (AccImportResult) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf("") }
    var exportSucceeded by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportResult by remember { mutableStateOf<AccImportResult?>(null) }
    var backupRows by remember { mutableStateOf<List<BackupRowUi>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showPlainConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var nameTagInput by remember(directory.backupNameTag) { mutableStateOf(directory.backupNameTag) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val readFailedMsg = context.getString(R.string.backup_read_failed)
    val plainParseFailedMsg = context.getString(R.string.backup_plain_parse_failed)
    val exportOkFmt = context.getString(R.string.backup_export_ok)
    val exportErrorDefaultMsg = context.getString(R.string.backup_export_error_default)
    val plainExportOkFmt = context.getString(R.string.backup_plain_export_ok)
    val plainExportErrorMsg = context.getString(R.string.backup_plain_export_error)
    val deleteRowFailedMsg = context.getString(R.string.backup_delete_row_failed)
    val exportUpgradeTitle = stringResource(R.string.export_upgrade_title)
    val exportUpgradeHint = stringResource(R.string.export_upgrade_hint)
    val exportUpgradeVerify = stringResource(R.string.export_upgrade_verify)
    val exportUpgradePasswordError = stringResource(R.string.export_upgrade_password_error)
    val masterPasswordHint = stringResource(R.string.master_password_rule_hint)
    val cancelLabel = stringResource(R.string.action_cancel)
    val importPasswordTitle = stringResource(R.string.backup_import_password_title)
    val importPasswordHint = stringResource(R.string.backup_import_password_hint)
    val importPasswordInvalid = stringResource(R.string.backup_import_password_invalid)
    val importBytesMissing = stringResource(R.string.backup_import_bytes_missing)
    val importFailedDefault = stringResource(R.string.backup_import_failed_default)
    val nameTagNormalized = normalizeBackupNameTag(nameTagInput)
    fun saveNameTag() {
        val normalized = nameTagNormalized ?: return
        if (directory.changeBackupNameTag(normalized)) nameTagInput = normalized
    }
    fun refreshFiles() {
        refreshing = true
        fileStore.refresh { rows ->
            backupRows = rows
            refreshing = false
        }
    }
    fun beginImport(read: ByteArray?) {
        pendingImportBytes?.fill(0)
        pendingImportBytes = read
        importError = if (read == null) readFailedMsg else ""
        importPassword = ""
        val isPlainAccountBox = read != null &&
                read.toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim() == ACCOUNTBOX_MAGIC
        if (isPlainAccountBox) {
            scope.launch {
                val bytes = pendingImportBytes ?: return@launch
                val result = withContext(Dispatchers.Default) { onImport(bytes, "") }
                if (result == null || result.isFailure) {
                    importError = result?.exceptionOrNull()?.message ?: plainParseFailedMsg
                    bytes.fill(0); pendingImportBytes = null
                } else {
                    bytes.fill(0); pendingImportBytes = null
                    pendingImportResult = result.getOrThrow(); showImportConfirmDialog = true
                }
            }
        } else {
            showImportPasswordDialog = true
        }
    }
    fun exportBackup() {
        exportError = ""
        exportSucceeded = false
        exporter.exportEncrypted { result ->
            exportSucceeded = result.isSuccess
            if (result.isSuccess) {
                exportError = exportOkFmt.format(result.getOrThrow())
                refreshFiles()
            } else if (result.exceptionOrNull() is ExportPasswordRequiredException) {
                exportPassword = ""
                exportPasswordError = ""
                showExportPasswordDialog = true
            } else {
                exportError = result.exceptionOrNull()?.message ?: exportErrorDefaultMsg
            }
        }
    }
    fun exportPlaintext() {
        exportError = ""
        exportSucceeded = false
        exporter.exportPlaintext { result ->
            exportSucceeded = result.isSuccess
            exportError = if (result.isSuccess) {
                plainExportOkFmt.format(result.getOrThrow())
            } else {
                result.exceptionOrNull()?.message ?: plainExportErrorMsg
            }
        }
    }
    fun requestDelete(row: BackupRowUi) {
        pendingDelete = PendingDelete(row.name) {
            fileStore.delete(row) { ok ->
                if (ok) refreshFiles() else exportError = deleteRowFailedMsg
            }
        }
    }
    LaunchedEffect(directory.directBackup, directory.storageAccessGranted, directory.backupTreeUri, directory.backupFolder) { refreshFiles() }

    AppScreen(title = stringResource(R.string.backup_title), onBack = onBack, actions = { TopBarTextButton(stringResource(R.string.backup_action_refresh), onClick = { refreshFiles() }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CollapsibleSection(title = stringResource(R.string.backup_settings_section), expanded = directory.directoryExpanded, onToggle = directory::toggleDirectoryExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsRow(stringResource(R.string.backup_location_row), backupLocationDisplay(directory.backupLocation), onClick = { showLocationDialog = true })
                    val nameTagInvalid = nameTagNormalized == null
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nameTagInput,
                                onValueChange = { nameTagInput = it },
                                label = { Text(stringResource(R.string.backup_name_tag_label)) },
                                singleLine = true,
                                isError = nameTagInvalid,
                                supportingText = if (nameTagInvalid) {
                                    { Text(stringResource(R.string.backup_name_tag_invalid), color = MaterialTheme.colorScheme.error) }
                                } else {
                                    { Text(stringResource(R.string.backup_name_tag_example)) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextActionButton(stringResource(R.string.action_save), enabled = !nameTagInvalid, onClick = ::saveNameTag)
                        }
                    }
                    if (directory.directBackup) {
                        TextActionButton(if (directory.storageAccessGranted) stringResource(R.string.backup_grant_access) else stringResource(R.string.backup_grant_all_files), directory::requestStorageAccess)
                    } else {
                        TextActionButton(if (directory.backupTreeUri == null) stringResource(R.string.backup_grant_create_dir) else stringResource(R.string.backup_regrant_dir), directory::chooseDirectory)
                    }
                    if (directory.directoryMessage.isNotBlank()) Text(directory.directoryMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            val ready = if (directory.directBackup) directory.storageAccessGranted else directory.backupTreeUri != null
            if (ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextActionButton(stringResource(R.string.backup_action_export), onClick = { if (exportError.isBlank()) showExportConfirm = true })
                    TextActionButton(stringResource(R.string.backup_action_export_plain), onClick = { showPlainConfirm = true })
                }
            }
            if (exportError.isNotBlank()) Text(exportError, color = if (exportSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.backup_saved_header), style = MaterialTheme.typography.titleMedium)
            if (!ready) EmptyState(if (directory.directBackup) stringResource(R.string.backup_need_all_files) else stringResource(R.string.backup_need_dir), Modifier.fillMaxWidth().weight(1f))
            else if (refreshing) EmptyState(stringResource(R.string.backup_loading), Modifier.fillMaxWidth().weight(1f))
            else if (backupRows.isEmpty()) EmptyState(stringResource(R.string.backup_empty_list), Modifier.fillMaxWidth().weight(1f))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(backupRows, key = { it.key }) { row ->
                    BackupRow(row.name, row.size, row.modified, { scope.launch { beginImport(withContext(Dispatchers.IO) { fileStore.read(row) }) } }, { requestDelete(row) })
                }
            }
        }
    }
    if (showLocationDialog) TextInputDialog(
        title = stringResource(R.string.backup_location_dialog_title),
        label = stringResource(R.string.backup_location_dialog_label),
        initial = directory.backupLocation,
        validate = { normalizeBackupLocation(it) != null },
        supportingText = stringResource(R.string.backup_location_dialog_hint),
        onDismiss = { showLocationDialog = false },
        onConfirm = { raw ->
            if (directory.changeBackupLocation(raw)) showLocationDialog = false
        }
    )
    if (showExportPasswordDialog) AlertDialog(onDismissRequest = { showExportPasswordDialog = false }, title = { Text(exportUpgradeTitle) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(exportUpgradeHint, style = MaterialTheme.typography.bodySmall)
            PasswordField(stringResource(R.string.unlock_password_label), exportPassword, { exportPassword = it; exportPasswordError = "" }, showPasswordToggle = true)
            if (exportPasswordError.isNotBlank()) Text(exportPasswordError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextActionButton(exportUpgradeVerify, onClick = {
            if (!isMasterPasswordValid(exportPassword)) {
                exportPasswordError = masterPasswordHint
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.Default) { exporter.prepareExport(exportPassword) }
                    if (result.isSuccess) {
                        exportPassword = ""
                        showExportPasswordDialog = false
                        exportBackup()
                    } else {
                        exportPasswordError = result.exceptionOrNull()?.message ?: exportUpgradePasswordError
                    }
                }
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton(cancelLabel, onClick = { showExportPasswordDialog = false }, textColor = MaterialTheme.colorScheme.primary) })
    if (showPlainConfirm) AlertDialog(
        onDismissRequest = { showPlainConfirm = false },
        title = { Text(stringResource(R.string.backup_plain_confirm_title)) },
        text = {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.backup_plain_confirm_intro, accountCount, groupCount))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.backup_plain_confirm_bold)) }
                    append(stringResource(R.string.backup_plain_confirm_tail1))
                    append(stringResource(R.string.backup_plain_confirm_tail2))
                }
            )
        },
        confirmButton = {
            TextActionButton(stringResource(R.string.backup_plain_confirm_action), onClick = {
                showPlainConfirm = false
                exportPlaintext()
            }, textColor = MaterialTheme.colorScheme.error)
        },
        dismissButton = { TextActionButton(cancelLabel, onClick = { showPlainConfirm = false }, textColor = MaterialTheme.colorScheme.primary) }
    )
    if (showImportPasswordDialog) AlertDialog(onDismissRequest = {
        showImportPasswordDialog = false
        pendingImportBytes?.fill(0)
        pendingImportBytes = null
    }, title = { Text(importPasswordTitle) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(importPasswordHint, style = MaterialTheme.typography.bodySmall)
            PasswordField(stringResource(R.string.backup_import_password_label), importPassword, { importPassword = it; importError = "" })
            if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextActionButton(stringResource(R.string.backup_import_verify_action), onClick = {
            if (!isMasterPasswordValid(importPassword)) importError = importPasswordInvalid
            else {
                val bytes = pendingImportBytes
                if (bytes == null) {
                    if (importError.isBlank()) importError = importBytesMissing
                } else {
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { onImport(bytes, importPassword) }
                        if (result == null || result.isFailure) importError = result?.exceptionOrNull()?.message ?: importFailedDefault
                        else {
                            bytes.fill(0); pendingImportBytes = null; showImportPasswordDialog = false
                            pendingImportResult = result.getOrThrow(); showImportConfirmDialog = true
                        }
                    }
                }
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton(cancelLabel, onClick = {
        showImportPasswordDialog = false; pendingImportBytes?.fill(0); pendingImportBytes = null
    }, textColor = MaterialTheme.colorScheme.primary) })

    pendingImportResult?.let { result ->
        if (showImportConfirmDialog) AlertDialog(onDismissRequest = { showImportConfirmDialog = false; pendingImportResult = null }, title = { Text(stringResource(R.string.backup_import_confirm_title)) }, text = { Text(stringResource(R.string.backup_import_confirm_message, result.vault.accounts.size, result.vault.groups.size)) }, confirmButton = {
            TextActionButton(stringResource(R.string.backup_import_confirm_action), onClick = { onApplyImport(result); showImportConfirmDialog = false; pendingImportResult = null }, textColor = MaterialTheme.colorScheme.primary)
        }, dismissButton = { TextActionButton(cancelLabel, onClick = { showImportConfirmDialog = false; pendingImportResult = null }) })
    }

    LaunchedEffect(exportError) {
        if (exportError.isNotBlank()) {
            delay(BACKUP_FEEDBACK_MILLIS)
            exportError = ""
            exportSucceeded = false
        }
    }
    if (showExportConfirm) ExportConfirmDialog(
        onConfirm = {
            showExportConfirm = false
            exportBackup()
        },
        onDismiss = { showExportConfirm = false }
    )

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            title = stringResource(R.string.backup_delete_title),
            message = stringResource(R.string.backup_delete_message, target.name),
            onConfirm = { target.delete(); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

private class PendingDelete(val name: String, val delete: () -> Unit)

@Composable
private fun BackupRow(name: String, size: Long, modified: Long, onImport: () -> Unit, onRequestDelete: () -> Unit) {
    val rowContext = androidx.compose.ui.platform.LocalContext.current
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatBackupSize(size)} · ${formatBackupTime(modified, rowContext)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextActionButton(stringResource(R.string.backup_row_import), onClick = onImport)
            DangerButton(stringResource(R.string.action_delete), onClick = onRequestDelete)
        }
    }
}

internal fun formatBackupSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

internal fun formatBackupTime(time: Long, context: android.content.Context): String = if (time <= 0) context.getString(R.string.backup_time_unknown) else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

@Preview(showBackground = true)
@Composable
private fun BackupScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        BackupScreen(
            fileStore = object : BackupFileStore {
                override fun refresh(onResult: (List<BackupRowUi>) -> Unit) {
                    onResult(
                        listOf(
                            BackupRowUi("preview/direct.acc", "20261022-101530-pixel8.acc", 2048, System.currentTimeMillis(), direct = true),
                            BackupRowUi("content://preview/saf.acc", "20261021-220001.acc", 1024, System.currentTimeMillis(), direct = false)
                        )
                    )
                }
                override fun read(row: BackupRowUi) = ByteArray(0)
                override fun delete(row: BackupRowUi, onDone: (Boolean) -> Unit) = onDone(true)
            },
            exporter = object : BackupExporter {
                override fun exportEncrypted(onDone: (Result<String>) -> Unit) = onDone(Result.success("20261022-101530-pixel8.acc"))
                override fun exportPlaintext(onDone: (Result<String>) -> Unit) = onDone(Result.success("account-plain-preview.json"))
                override fun prepareExport(password: String) = Result.success(Unit)
            },
            directory = object : BackupDirectoryOps {
                override val directBackup = true
                override val storageAccessGranted = true
                override val backupTreeUri: String? = null
                override val backupLocation = ""
                override val backupFolder = "Account/account"
                override val backupNameTag = ""
                override val directoryMessage = ""
                override val directoryExpanded = true
                override fun toggleDirectoryExpanded() {}
                override fun changeBackupNameTag(raw: String) = true
                override fun changeBackupLocation(raw: String) = true
                override fun changeBackupFolder(raw: String) = true
                override fun chooseDirectory() {}
                override fun requestStorageAccess() {}
            },
            accountCount = 3,
            groupCount = 2,
            onImport = { _, _ -> Result.failure(RuntimeException("预览")) },
            onApplyImport = {},
            onBack = {}
        )
    }
}
