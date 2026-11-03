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
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
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
        importError = if (read == null) "无法读取备份" else ""
        importPassword = ""
        val isPlainAccountBox = read != null &&
                read.toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim() == ACCOUNTBOX_MAGIC
        if (isPlainAccountBox) {
            scope.launch {
                val bytes = pendingImportBytes ?: return@launch
                val result = withContext(Dispatchers.Default) { onImport(bytes, "") }
                if (result == null || result.isFailure) {
                    importError = result?.exceptionOrNull()?.message ?: "账号本子文件解析失败"
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
                exportError = "导出成功：${result.getOrThrow()}"
                refreshFiles()
            } else if (result.exceptionOrNull() is ExportPasswordRequiredException) {
                exportPassword = ""
                exportPasswordError = ""
                showExportPasswordDialog = true
            } else {
                exportError = result.exceptionOrNull()?.message ?: "备份生成失败"
            }
        }
    }
    fun exportPlaintext() {
        exportError = ""
        exportSucceeded = false
        exporter.exportPlaintext { result ->
            exportSucceeded = result.isSuccess
            exportError = if (result.isSuccess) {
                "已导出明文到下载目录：${result.getOrThrow()}（请自行妥善保管并及时删除）"
            } else {
                result.exceptionOrNull()?.message ?: "明文导出失败"
            }
        }
    }
    fun requestDelete(row: BackupRowUi) {
        pendingDelete = PendingDelete(row.name) {
            fileStore.delete(row) { ok ->
                if (ok) refreshFiles() else exportError = "删除失败"
            }
        }
    }
    LaunchedEffect(directory.directBackup, directory.storageAccessGranted, directory.backupTreeUri, directory.backupFolder) { refreshFiles() }

    AppScreen(title = "加密备份", onBack = onBack, actions = { TopBarTextButton("刷新", onClick = { refreshFiles() }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CollapsibleSection(title = "备份设置", expanded = directory.directoryExpanded, onToggle = directory::toggleDirectoryExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsRow("备份位置", backupLocationDisplay(directory.backupLocation), onClick = { showLocationDialog = true })
                    val nameTagInvalid = nameTagNormalized == null
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nameTagInput,
                                onValueChange = { nameTagInput = it },
                                label = { Text("文件名标签（可选）") },
                                singleLine = true,
                                isError = nameTagInvalid,
                                supportingText = if (nameTagInvalid) {
                                    { Text("含无法用于文件名的字符，改一下再存", color = MaterialTheme.colorScheme.error) }
                                } else {
                                    { Text("例：pixel8 → 20261022-101530-pixel8.acc；留空 = 20261022-101530.acc") }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextActionButton("保存", enabled = !nameTagInvalid, onClick = ::saveNameTag)
                        }
                    }
                    if (directory.directBackup) {
                        TextActionButton(if (directory.storageAccessGranted) "授予权限" else "授予文件访问权限", directory::requestStorageAccess)
                    } else {
                        TextActionButton(if (directory.backupTreeUri == null) "授权并创建目录" else "重新授权目录", directory::chooseDirectory)
                    }
                    if (directory.directoryMessage.isNotBlank()) Text(directory.directoryMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            val ready = if (directory.directBackup) directory.storageAccessGranted else directory.backupTreeUri != null
            if (ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextActionButton("导出备份", onClick = { if (exportError.isBlank()) showExportConfirm = true })
                    TextActionButton("导出明文", onClick = { showPlainConfirm = true })
                }
            }
            if (exportError.isNotBlank()) Text(exportError, color = if (exportSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("已保存的备份", style = MaterialTheme.typography.titleMedium)
            if (!ready) EmptyState(if (directory.directBackup) "请先授予「所有文件访问」权限" else "请先授权备份目录", Modifier.fillMaxWidth().weight(1f))
            else if (refreshing) EmptyState("正在加载备份…", Modifier.fillMaxWidth().weight(1f))
            else if (backupRows.isEmpty()) EmptyState("暂无 .acc 文件，点击右上角「刷新」", Modifier.fillMaxWidth().weight(1f))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(backupRows, key = { it.key }) { row ->
                    BackupRow(row.name, row.size, row.modified, { scope.launch { beginImport(withContext(Dispatchers.IO) { fileStore.read(row) }) } }, { requestDelete(row) })
                }
            }
        }
    }
    if (showLocationDialog) TextInputDialog(
        title = "备份位置",
        label = "内部存储下的位置前缀",
        initial = directory.backupLocation,
        validate = { normalizeBackupLocation(it) != null },
        supportingText = "结构固定为 <位置>/Account/account；0 或留空 = 内部存储根。不要以 / 开头，不支持 ..。例：Download",
        onDismiss = { showLocationDialog = false },
        onConfirm = { raw ->
            if (directory.changeBackupLocation(raw)) showLocationDialog = false
        }
    )
    if (showExportPasswordDialog) AlertDialog(onDismissRequest = { showExportPasswordDialog = false }, title = { Text("完成导出升级") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("首次导出需要验证一次主密码。之后指纹或主密码解锁都可直接导出。", style = MaterialTheme.typography.bodySmall)
            PasswordField("主密码", exportPassword, { exportPassword = it; exportPasswordError = "" }, showPasswordToggle = true)
            if (exportPasswordError.isNotBlank()) Text(exportPasswordError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextActionButton("验证并导出", onClick = {
            if (!isMasterPasswordValid(exportPassword)) {
                exportPasswordError = MASTER_PASSWORD_RULE_HINT
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.Default) { exporter.prepareExport(exportPassword) }
                    if (result.isSuccess) {
                        exportPassword = ""
                        showExportPasswordDialog = false
                        exportBackup()
                    } else {
                        exportPasswordError = result.exceptionOrNull()?.message ?: "主密码验证失败"
                    }
                }
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton("取消", onClick = { showExportPasswordDialog = false }, textColor = MaterialTheme.colorScheme.primary) })
    if (showPlainConfirm) AlertDialog(
        onDismissRequest = { showPlainConfirm = false },
        title = { Text("导出明文文件？") },
        text = {
            Text(
                buildAnnotatedString {
                    append("将把 $accountCount 个账号、$groupCount 个分组的")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("全部密码与两步验证密钥") }
                    append("以账号本子明文格式（ACCOUNTBOX_JSON_13）写入系统下载目录（Download）。\n\n")
                    append("该文件没有任何加密，任何人拿到即可登录你的所有账号，且应用内无法删除它。绑定关系（网址/应用）不会随明文导出保留。确认导出？")
                }
            )
        },
        confirmButton = {
            TextActionButton("确认导出", onClick = {
                showPlainConfirm = false
                exportPlaintext()
            }, textColor = MaterialTheme.colorScheme.error)
        },
        dismissButton = { TextActionButton("取消", onClick = { showPlainConfirm = false }, textColor = MaterialTheme.colorScheme.primary) }
    )
    if (showImportPasswordDialog) AlertDialog(onDismissRequest = {
        showImportPasswordDialog = false
        pendingImportBytes?.fill(0)
        pendingImportBytes = null
    }, title = { Text("恢复加密备份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文件已选择，请输入该备份设置的密码以验证并解密。", style = MaterialTheme.typography.bodySmall)
            PasswordField("备份密码（4-20 个字符）", importPassword, { importPassword = it; importError = "" })
            if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextActionButton("验证并继续", onClick = {
            if (!isMasterPasswordValid(importPassword)) importError = "备份密码长度需为 4-20 个字符"
            else {
                val bytes = pendingImportBytes
                if (bytes == null) {
                    if (importError.isBlank()) importError = "备份文件读取失败，请刷新后重试"
                } else {
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { onImport(bytes, importPassword) }
                        if (result == null || result.isFailure) importError = result?.exceptionOrNull()?.message ?: "备份密码错误或文件已损坏"
                        else {
                            bytes.fill(0); pendingImportBytes = null; showImportPasswordDialog = false
                            pendingImportResult = result.getOrThrow(); showImportConfirmDialog = true
                        }
                    }
                }
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton("取消", onClick = {
        showImportPasswordDialog = false; pendingImportBytes?.fill(0); pendingImportBytes = null
    }, textColor = MaterialTheme.colorScheme.primary) })

    pendingImportResult?.let { result ->
        if (showImportConfirmDialog) AlertDialog(onDismissRequest = { showImportConfirmDialog = false; pendingImportResult = null }, title = { Text("确认恢复") }, text = { Text("将替换当前密码库，导入 ${result.vault.accounts.size} 个账号和 ${result.vault.groups.size} 个分组，并应用备份中的软件设置。") }, confirmButton = {
            TextActionButton("确认恢复", onClick = { onApplyImport(result); showImportConfirmDialog = false; pendingImportResult = null }, textColor = MaterialTheme.colorScheme.primary)
        }, dismissButton = { TextActionButton("取消", onClick = { showImportConfirmDialog = false; pendingImportResult = null }) })
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
            title = "删除备份",
            message = "确定删除「${target.name}」吗？此操作不可撤销。",
            onConfirm = { target.delete(); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

private class PendingDelete(val name: String, val delete: () -> Unit)

@Composable
private fun BackupRow(name: String, size: Long, modified: Long, onImport: () -> Unit, onRequestDelete: () -> Unit) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatBackupSize(size)} · ${formatBackupTime(modified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextActionButton("导入", onClick = onImport)
            DangerButton("删除", onClick = onRequestDelete)
        }
    }
}

internal fun formatBackupSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

internal fun formatBackupTime(time: Long): String = if (time <= 0) "未知时间" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

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
