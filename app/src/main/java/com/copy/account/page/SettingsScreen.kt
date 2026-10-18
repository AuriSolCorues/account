/**
 * 职责：设置中枢——顶部「一键备份」动作行 + 四个分组入口（安全设置 / 主题与语言 / 备份与数据 / 关于）。
 *       自身只持有备份动作的执行反馈；设置项的读写与弹窗都在对应二级页。
 *       一键备份复用备份页同一个 BackupExporter 实例（AccountApp 提升），流程与「导出备份」按钮同源：
 *       门禁（权限/解锁）内建于 exporter，首次导出经主密码弹窗（prepareExport）后自动续跑。
 * 架构位置：AccountApp 的 AppPage.Settings 分支；行组件来自 ui/components/Rows.kt。
 * Python 类比：≈ 路由表/目录页——本页 ≈ 索引，各分组 ≈ 被它指向的子函数。
 */
package com.copy.account.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    quickBackup: com.copy.account.page.BackupExporter,
    onOpenSecurity: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 备份动作状态：busy 防重入；message 非空时值位显示反馈（保留到下次点击；错误另起红字行）。
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    // 首次导出（导出密钥未缓存）的主密码弹窗；密码错误留在弹窗内提示。
    var showPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf("") }

    /** 与备份页 exportBackup 同源：调 exporter，密码门禁分支弹主密码框。 */
    fun exportBackup() {
        message = ""
        busy = true
        quickBackup.exportEncrypted { result ->
            busy = false
            val error = result.exceptionOrNull()
            if (result.isSuccess) {
                message = "已备份：${result.getOrThrow()}"
                messageIsError = false
            } else if (error is ExportPasswordRequiredException) {
                exportPassword = ""
                passwordError = ""
                showPasswordDialog = true
            } else {
                message = error?.message ?: "备份生成失败"
                messageIsError = true
            }
        }
    }

    AppListScreen("设置", onBack) {
        // 一键备份：设置列表首项（安全设置之上），执行体就是备份页「导出备份」那个 exporter 函数。
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsRow("一键备份", if (busy) "备份中…" else if (message.isBlank()) "备份" else message, onClick = { if (!busy) exportBackup() })
                if (messageIsError && message.isNotBlank()) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                    )
                }
            }
        }
        item { SettingsRow("安全设置", "打开", onClick = onOpenSecurity) }
        item { SettingsRow("主题与语言", "打开", onClick = onOpenAppearance) }
        item { SettingsRow("备份与数据", "打开", onClick = onOpenBackup) }
        // 版本号直接露在中枢上，不用进「关于」页也知道自己是哪一版。
        item { SettingsRow("关于", BuildConfig.VERSION_NAME, onClick = onOpenAbout) }
    }
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
            onOpenAbout = {},
            onBack = {}
        )
    }
}
