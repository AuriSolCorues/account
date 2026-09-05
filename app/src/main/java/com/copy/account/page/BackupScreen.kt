package com.copy.account.page

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.security.AccImportResult
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.data.backup.BackupEntry
import com.copy.account.data.backup.FileBackupEntry
import com.copy.account.data.backup.listBackupFiles
import com.copy.account.data.backup.listFileBackups
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BackupScreen(
    onBack: () -> Unit,
    directBackup: Boolean,
    storageAccessGranted: Boolean,
    backupTreeUri: String?,
    directoryMessage: String,
    onChooseDirectory: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onExportBackup: () -> Result<String>,
    onReadBackup: (Uri) -> Result<ByteArray>,
    onDeleteBackup: (Uri) -> Result<Unit>,
    onReadFileBackup: (File) -> Result<ByteArray>,
    onDeleteFileBackup: (File) -> Result<Unit>,
    onImportBackup: (ByteArray, String) -> Result<AccImportResult>,
    onApplyImport: (AccImportResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportReminder by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf("") }
    var exportSucceeded by remember { mutableStateOf(false) }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportResult by remember { mutableStateOf<AccImportResult?>(null) }
    var files by remember { mutableStateOf(emptyList<BackupEntry>()) }
    var fileBackups by remember { mutableStateOf(emptyList<FileBackupEntry>()) }
    var fileError by remember { mutableStateOf("") }
    // 目录列举（SAF 的 DocumentsContract 查询尤慢）放 IO 线程，免进页/刷新卡顿。
    fun refreshFiles() {
        scope.launch {
            if (directBackup) {
                val direct = withContext(Dispatchers.IO) { listFileBackups() }
                fileBackups = direct.getOrDefault(emptyList())
                fileError = direct.exceptionOrNull()?.message ?: ""
            } else {
                val saf = withContext(Dispatchers.IO) { listBackupFiles(context, backupTreeUri) }
                files = saf.getOrDefault(emptyList())
                fileError = saf.exceptionOrNull()?.message ?: ""
            }
        }
    }
    /** 读取结果转导入流程：清旧缓冲、记错误、弹密码框。 */
    fun beginImport(read: Result<ByteArray>) {
        pendingImportBytes?.fill(0)
        pendingImportBytes = read.getOrNull()
        importError = read.exceptionOrNull()?.message?.let { "无法读取备份：$it" } ?: ""
        importPassword = ""
        showImportPasswordDialog = true
    }
    LaunchedEffect(directBackup, storageAccessGranted, backupTreeUri) { refreshFiles() }

    // 两轨（SAF/直写）统一成一列行模型，只留读写回调差异。
    val rows = if (directBackup) fileBackups.map { entry ->
        BackupRowUi(
            key = entry.file.absolutePath,
            name = entry.file.name,
            size = entry.size,
            modified = entry.modified,
            onImport = { scope.launch { beginImport(withContext(Dispatchers.IO) { onReadFileBackup(entry.file) }) } },
            onDelete = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { onDeleteFileBackup(entry.file) }
                    if (result.isSuccess) refreshFiles() else exportError = result.exceptionOrNull()?.message ?: "删除失败"
                }
            }
        )
    } else files.map { entry ->
        BackupRowUi(
            key = entry.file.uri.toString(),
            name = entry.file.name ?: "account.acc",
            size = entry.size,
            modified = entry.modified,
            onImport = { scope.launch { beginImport(withContext(Dispatchers.IO) { onReadBackup(entry.file.uri) }) } },
            onDelete = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { onDeleteBackup(entry.file.uri) }
                    if (result.isSuccess) refreshFiles() else exportError = result.exceptionOrNull()?.message ?: "删除失败"
                }
            }
        )
    }

    AppScreen(title = "加密备份", onBack = onBack, actions = { TextActionButton("刷新", onClick = { refreshFiles() }, textColor = LocalAccountThemePalette.current.topBarText) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("备份目录", style = MaterialTheme.typography.titleMedium)
            val description = when {
                directBackup -> if (storageAccessGranted) "已固定使用 内部存储/backups/account 文件夹。"
                else "需授予「所有文件访问」权限，备份固定保存于 内部存储/backups/account。"
                backupTreeUri == null -> "尚未授权。点击下方按钮选择一个可写目录，应用会自动创建 backups/account。"
                else -> "已固定使用授权目录下的 backups/account 文件夹。"
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (directBackup) {
                    TextActionButton(if (storageAccessGranted) "重新授予文件访问" else "授予文件访问权限", onRequestStorageAccess)
                    if (storageAccessGranted) TextActionButton("导出 .acc", onClick = { exportError = ""; exportSucceeded = false; showExportReminder = true })
                } else {
                    TextActionButton(if (backupTreeUri == null) "授权并创建目录" else "重新授权目录", onChooseDirectory)
                    if (backupTreeUri != null) TextActionButton("导出 .acc", onClick = { exportError = ""; exportSucceeded = false; showExportReminder = true })
                }
            }
            if (directoryMessage.isNotBlank()) Text(directoryMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (fileError.isNotBlank()) Text(fileError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (exportError.isNotBlank()) Text(exportError, color = if (exportSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("已保存的备份", style = MaterialTheme.typography.titleMedium)
            val notReady = if (directBackup) !storageAccessGranted else backupTreeUri == null
            if (notReady) EmptyState(if (directBackup) "请先授予「所有文件访问」权限" else "请先授权备份目录", Modifier.fillMaxWidth().weight(1f))
            else if (rows.isEmpty()) EmptyState("暂无 .acc 文件", Modifier.fillMaxWidth().weight(1f))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { row ->
                    BackupRow(row.name, row.size, row.modified, row.onImport, row.onDelete)
                }
            }
        }
    }
    if (showExportReminder) AlertDialog(onDismissRequest = { showExportReminder = false }, title = { Text("导出加密备份") }, text = { Text("导出文件使用当前主密码加密。导入时必须输入相同的主密码，请务必记住。", style = MaterialTheme.typography.bodySmall) }, confirmButton = {
        // 导出含全库 AES-GCM，放后台线程，免主线程冻结。
        TextActionButton("确认导出", onClick = {
            showExportReminder = false
            scope.launch {
                val result = withContext(Dispatchers.Default) { onExportBackup() }
                exportSucceeded = result.isSuccess
                exportError = if (result.isSuccess) "导出成功：${result.getOrThrow()}" else result.exceptionOrNull()?.message ?: "备份生成失败，请先解锁后重试"
                if (result.isSuccess) refreshFiles()
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton("取消", onClick = { showExportReminder = false }) })

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
                    // 导入验证含 PBKDF2（数十万至二百万次迭代），放后台线程，否则可致 ANR。
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { onImportBackup(bytes, importPassword) }
                        if (result.isFailure) importError = result.exceptionOrNull()?.message ?: "备份密码错误或文件已损坏"
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
}

/** 列表行模型：SAF 与直写两轨统一成一列，只留读写回调差异。 */
private data class BackupRowUi(
    val key: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val onImport: () -> Unit,
    val onDelete: () -> Unit
)

/** 备份列表行：名称 + 大小/时间 + 导入/删除。 */
@Composable
private fun BackupRow(name: String, size: Long, modified: Long, onImport: () -> Unit, onDelete: () -> Unit) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatBackupSize(size)} · ${formatBackupTime(modified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextActionButton("导入", onClick = onImport)
            DangerButton("删除", onClick = onDelete)
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
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        BackupScreen(
            onBack = {},
            directBackup = true,
            storageAccessGranted = true,
            backupTreeUri = null,
            directoryMessage = "",
            onChooseDirectory = {},
            onRequestStorageAccess = {},
            onExportBackup = { Result.success("preview.acc") },
            onReadBackup = { Result.success(ByteArray(0)) },
            onDeleteBackup = { Result.success(Unit) },
            onReadFileBackup = { Result.success(ByteArray(0)) },
            onDeleteFileBackup = { Result.success(Unit) },
            onImportBackup = { _, _ -> Result.failure(RuntimeException("预览")) },
            onApplyImport = {}
        )
    }
}
