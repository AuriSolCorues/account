package com.copy.account.page

import android.content.Intent
import android.os.SystemClock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import com.copy.account.BuildConfig
import com.copy.account.runtime.launchExtensionSettings
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.ExportConfirmDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    quickBackup: com.copy.account.page.BackupExporter,
    onOpenSecurity: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLogs: () -> Unit,
    extensionLaunch: Intent?,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultIsError by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf("") }

    fun exportBackup() {
        resultMessage = ""
        busy = true
        quickBackup.exportEncrypted { result ->
            busy = false
            val error = result.exceptionOrNull()
            if (result.isSuccess) {
                resultMessage = "已导出 ${result.getOrThrow()}"
                resultIsError = false
            } else if (error is ExportPasswordRequiredException) {
                exportPassword = ""
                passwordError = ""
                showPasswordDialog = true
            } else {
                resultMessage = error?.message ?: "备份生成失败"
                resultIsError = true
            }
        }
    }

    AppListScreen("设置", onBack) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    "一键备份",
                    when {
                        busy -> "备份中…"
                        resultMessage.isBlank() -> "备份"
                        else -> if (resultIsError) "导出失败" else "导出成功"
                    },
                    onClick = {
                        if (!busy && resultMessage.isBlank()) {
                            showExportConfirm = true
                        }
                    }
                )
                if (resultMessage.isNotBlank()) {
                    Text(
                        resultMessage,
                        color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                    )
                }
            }
        }
        item { SettingsRow("安全设置", "打开", onClick = onOpenSecurity) }
        item { SettingsRow("主题与语言", "打开", onClick = onOpenAppearance) }
        item { SettingsRow("备份与数据", "打开", onClick = onOpenBackup) }
        if (extensionLaunch != null) {
            item {
                SettingsRow("扩展", "打开", onClick = { launchExtensionSettings(context) })
            }
        }
        item { SettingsRow("操作日志", "打开", onClick = onOpenLogs) }
        item { SettingsRow("关于", BuildConfig.VERSION_NAME, onClick = onOpenAbout) }
    }
    LaunchedEffect(resultMessage) {
        if (resultMessage.isNotBlank()) {
            delay(BACKUP_FEEDBACK_MILLIS)
            resultMessage = ""
        }
    }
    if (showExportConfirm) ExportConfirmDialog(
        onConfirm = {
            showExportConfirm = false
            exportBackup()
        },
        onDismiss = { showExportConfirm = false }
    )
    if (showPasswordDialog) AlertDialog(onDismissRequest = { showPasswordDialog = false }, title = { Text("完成导出升级") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("首次导出需要验证一次主密码。之后指纹或主密码解锁都可直接导出。", style = MaterialTheme.typography.bodySmall)
            PasswordField("主密码", exportPassword, { exportPassword = it; passwordError = "" }, showPasswordToggle = true)
            if (passwordError.isNotBlank()) Text(passwordError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextActionButton("验证并导出", onClick = {
            if (!isMasterPasswordValid(exportPassword)) {
                passwordError = MASTER_PASSWORD_RULE_HINT
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.Default) { quickBackup.prepareExport(exportPassword) }
                    if (result.isSuccess) {
                        exportPassword = ""
                        showPasswordDialog = false
                        exportBackup()
                    } else {
                        passwordError = result.exceptionOrNull()?.message ?: "主密码验证失败"
                    }
                }
            }
        }, textColor = MaterialTheme.colorScheme.primary)
    }, dismissButton = { TextActionButton("取消", onClick = { showPasswordDialog = false }, textColor = MaterialTheme.colorScheme.primary) })
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        SettingsScreen(
            quickBackup = object : com.copy.account.page.BackupExporter {
                override fun exportEncrypted(onDone: (Result<String>) -> Unit) = onDone(Result.success("account-preview.acc"))
                override fun exportPlaintext(onDone: (Result<String>) -> Unit) = onDone(Result.success("account-plain-preview.json"))
                override fun prepareExport(password: String) = Result.success(Unit)
            },
            onOpenSecurity = {},
            onOpenAppearance = {},
            onOpenBackup = {},
            extensionLaunch = null,
            onOpenLogs = {},
            onOpenAbout = {},
            onBack = {}
        )
    }
}
