/**
 * 职责：备份页 UI 壳——目录授权状态展示、导出提醒、两轨备份列表（导入/删除）、两段式导入流程。
 *       props 已收拢为三个语义接口（见 BackupContracts.kt）：BackupFileStore（文件列举/读/删）、
 *       BackupExporter（加密/明文导出）、BackupDirectoryOps（目录状态与动作）；
 *       文件 IO、加解密与线程切换全部经接口实现方（AccountApp）完成，本页只管展示、弹层与流程状态。
 * 架构位置：AccountApp 的 AppPage.BackupFiles 分支。
 * Python 类比：两段式导入 ≈ 向导（wizard）——读文件暂存 bytes，验密后暂存 result，
 *           用户确认才真正覆盖当前库；Result<T> ≈ 显式的「值或异常」返回约定。
 */
package com.copy.account.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.security.AccImportResult
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.data.backup.normalizeBackupFolder
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
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
    // 两段式导入状态机：bytes=已读未验证的文件，result=已验证未应用的库；取消或失败即清零字节。
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportResult by remember { mutableStateOf<AccImportResult?>(null) }
    var backupRows by remember { mutableStateOf<List<BackupRowUi>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showPlainConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    // 列举经 fileStore.refresh：异步与线程切换在实现方，完成后主线程回调归一行数据。
    fun refreshFiles() {
        refreshing = true
        fileStore.refresh { rows ->
            backupRows = rows
            refreshing = false
        }
    }
    /** 读取结果转导入流程：清旧缓冲、记错误、弹密码框。 */
    fun beginImport(read: ByteArray?) {
        pendingImportBytes?.fill(0)
        pendingImportBytes = read
        importError = if (read == null) "无法读取备份" else ""
        importPassword = ""
        showImportPasswordDialog = true
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
    // 刻意不调 refreshFiles()：产物是 .json，而列表只 filter .acc，明文文件本来就不该出现在这里。
    fun exportPlaintext() {
        exportError = ""
        exportSucceeded = false
        exporter.exportPlaintext { result ->
            exportSucceeded = result.isSuccess
            exportError = if (result.isSuccess) {
                "已导出明文：${result.getOrThrow()}（请自行妥善保管并及时删除）"
            } else {
                result.exceptionOrNull()?.message ?: "明文导出失败"
            }
        }
    }
    // 删除此前点一下就真删，且两条轨各写一遍执行体。抽出后确认逻辑只此一份，
    // 轨差异收进 fileStore.delete(row)。
    fun requestDelete(row: BackupRowUi) {
        pendingDelete = PendingDelete(row.name) {
            fileStore.delete(row) { ok ->
                if (ok) refreshFiles() else exportError = "删除失败"
            }
        }
    }
    // backupFolder 等目录状态变化时靠 key 触发一次重新列举，否则列表还停在旧目录。
    LaunchedEffect(directory.directBackup, directory.storageAccessGranted, directory.backupTreeUri, directory.backupFolder) { refreshFiles() }

    AppScreen(title = "加密备份", onBack = onBack, actions = { TextActionButton("刷新", onClick = { refreshFiles() }, textColor = LocalAccountThemePalette.current.topBarText) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("备份目录", style = MaterialTheme.typography.titleMedium)
            val description = when {
                directory.directBackup -> if (directory.storageAccessGranted) "已固定使用 内部存储/${directory.backupFolder} 文件夹。"
                else "需授予「所有文件访问」权限，备份固定保存于 内部存储/${directory.backupFolder}。"
                directory.backupTreeUri == null -> "尚未授权。点击下方按钮选择一个可写目录，应用会自动创建 ${directory.backupFolder}。"
                else -> "已固定使用授权目录下的 ${directory.backupFolder} 文件夹。"
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsRow("备份文件夹", directory.backupFolder, onClick = { showFolderDialog = true })
            // 两轨的授权状态在这里收一次：下面的按钮区与 EmptyState 共用同一个判定。
            val ready = if (directory.directBackup) directory.storageAccessGranted else directory.backupTreeUri != null
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (directory.directBackup) {
                    TextActionButton(if (directory.storageAccessGranted) "授予权限" else "授予文件访问权限", directory::requestStorageAccess)
                } else {
                    TextActionButton(if (directory.backupTreeUri == null) "授权并创建目录" else "重新授权目录", directory::chooseDirectory)
                }
                // 两个导出按钮只写一份：文案与行为都不该因 Android 版本而分叉，
                // 靠复制粘贴保持一致正是上次把「导出备份」改岔的原因。
                if (ready) {
                    TextActionButton("导出备份", onClick = ::exportBackup)
                    TextActionButton("导出明文", onClick = { showPlainConfirm = true })
                }
            }
            if (directory.directoryMessage.isNotBlank()) Text(directory.directoryMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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
    if (showFolderDialog) TextInputDialog(
        title = "备份文件夹",
        label = "内部存储下的子路径",
        initial = directory.backupFolder,
        // 同一套校验：非法输入（绝对路径 / ..）时把保存键置灰，用户点不动。
        validate = { normalizeBackupFolder(it) != null },
        supportingText = "留空使用默认 backups/account。不要以 / 开头，不支持 ..。例：Download/账本备份",
        onDismiss = { showFolderDialog = false },
        onConfirm = { raw ->
            // 校验已保证非 null；这里仍走返回值判断，不写 ?: 兜底掩盖失败。
            if (directory.changeBackupFolder(raw)) showFolderDialog = false
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
            // 这段话是本功能唯一的风险提示，不能删也不能压缩成一句「确定吗」——
            // 产物一旦落盘就无法回收，删除也只能靠文件管理器。
            // 强调用 SpanStyle 而非 Markdown 的 **：**：Compose 的 Text 不解析 Markdown，
            // 写星号会在界面上显示成两个星号（同一个坑：AboutScreen 那次问过 []()）。
            Text(
                buildAnnotatedString {
                    append("将把 $accountCount 个账号、$groupCount 个分组的")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("全部密码与两步验证密钥") }
                    append("以明文 JSON 写入 ${directory.backupFolder}。\n\n")
                    append("该文件没有任何加密，任何人拿到即可登录你的所有账号，且应用内无法删除它。确认导出？")
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
            if (!isMasterPasswordValid(importPassword)) importError = "备份密码长度需为 4-20 个字符" // 与 AccCodec 导入 require 同文案，语境是备份而非主密码
            else {
                val bytes = pendingImportBytes
                if (bytes == null) {
                    if (importError.isBlank()) importError = "备份文件读取失败，请刷新后重试"
                } else {
                    // 导入验证含 PBKDF2（数十万至二百万次迭代），放后台线程，否则可致 ANR。
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

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            title = "删除备份",
            message = "确定删除「${target.name}」吗？此操作不可撤销。",
            onConfirm = { target.delete(); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * 待确认删除的备份：name 只给弹窗文案用；delete 仅在用户点「删除」后才执行。
 * 删备份不可逆（文件本身没了），故必须过一次 DeleteConfirmDialog，不能点一下就真删。
 */
private class PendingDelete(val name: String, val delete: () -> Unit)

/** 备份列表行：名称 + 大小/时间 + 导入/删除。 */
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
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        BackupScreen(
            fileStore = object : BackupFileStore {
                override fun refresh(onResult: (List<BackupRowUi>) -> Unit) {
                    onResult(
                        listOf(
                            BackupRowUi("preview/direct.acc", "account-2026-09-30.acc", 2048, System.currentTimeMillis(), direct = true),
                            BackupRowUi("content://preview/saf.acc", "account-2026-09-29.acc", 1024, System.currentTimeMillis(), direct = false)
                        )
                    )
                }
                override fun read(row: BackupRowUi) = ByteArray(0)
                override fun delete(row: BackupRowUi, onDone: (Boolean) -> Unit) = onDone(true)
            },
            exporter = object : BackupExporter {
                override fun exportEncrypted(onDone: (Result<String>) -> Unit) = onDone(Result.success("account-2026-09-30.acc"))
                override fun exportPlaintext(onDone: (Result<String>) -> Unit) = onDone(Result.success("account-plain-preview.json"))
                override fun prepareExport(password: String) = Result.success(Unit)
            },
            directory = object : BackupDirectoryOps {
                override val directBackup = true
                override val storageAccessGranted = true
                override val backupTreeUri: String? = null
                override val backupFolder = "backups/account"
                override val directoryMessage = ""
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
