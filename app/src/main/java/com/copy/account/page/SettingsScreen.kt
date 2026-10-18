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
import com.copy.account.security.ExportPasswordRequiredException
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
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
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 备份动作状态：busy 防重入；resultMessage 非空时值位显「导出成功/导出失败」、
    // 行下显示详情小字，4 秒后两者一起消失（LaunchedEffect 定时清场），期间可再次点击导出。
    var busy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultIsError by remember { mutableStateOf(false) }
    // 点击行先弹确认框（防误触 + 主密码警示），确定才真正导出。
    var showExportConfirm by remember { mutableStateOf(false) }
    // 首次导出（导出密钥未缓存）的主密码弹窗；密码错误留在弹窗内提示。
    var showPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf("") }

    /** 与备份页 exportBackup 同源：调 exporter，密码门禁分支弹主密码框。 */
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
        // 一键备份：设置列表首项（安全设置之上），执行体就是备份页「导出备份」那个 exporter 函数。
        // 值位只报结果状态（导出成功/导出失败/备份中…）；文件名等详情走行下小字，不挤值位。
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
                        if (!busy) {
                            resultMessage = ""
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
        // 版本号直接露在中枢上，不用进「关于」页也知道自己是哪一版。
        item { SettingsRow("关于", BuildConfig.VERSION_NAME, onClick = onOpenAbout) }
    }
    // 反馈 4 秒自动消失：key 挂 resultMessage，新反馈进来重新计时；清空后值位回「备份」。
    LaunchedEffect(resultMessage) {
        if (resultMessage.isNotBlank()) {
            delay(4000)
            resultMessage = ""
        }
    }
    if (showExportConfirm) AlertDialog(
        onDismissRequest = { showExportConfirm = false },
        title = { Text("导出加密备份？") },
        text = {
            Text(
                "将把当前全部账号与分组加密导出到备份文件夹。" +
                        "备份靠主密码加密，请牢记主密码——遗忘后备份将无法恢复。"
            )
        },
        confirmButton = {
            TextActionButton("确定导出", onClick = {
                showExportConfirm = false
                exportBackup()
            }, textColor = MaterialTheme.colorScheme.primary)
        },
        dismissButton = { TextActionButton("取消", onClick = { showExportConfirm = false }, textColor = MaterialTheme.colorScheme.primary) }
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
            onOpenAbout = {},
            onBack = {}
        )
    }
}
