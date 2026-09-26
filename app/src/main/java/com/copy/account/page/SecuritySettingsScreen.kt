/**
 * 职责：安全设置二级页——禁止截图、自动锁定、生物识别、修改主密码，以及剪贴板敏感内容自动清除。
 * 架构位置：AccountApp 的 AppPage.SecuritySettings 分支（由设置中枢进入）；行组件来自
 *           ui/components/Rows.kt，单选弹窗复用 ui/components/Dialogs.kt 的 ChoiceDialog。
 * Python 类比：从原大设置页里切出的一块——≈ 把一个超长函数按职责拆成几个同名职责的小函数，
 *           数据与事件仍全部由 AccountApp 经 props 进出。
 */
package com.copy.account.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.ChoiceDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsHeader
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.SettingsSwitchRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
import kotlinx.coroutines.launch

@Composable
internal fun SecuritySettingsScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onChangeMasterPassword: suspend (String) -> Result<Unit>,
    autoLockMinutes: Int,
    onAutoLockChange: (Int) -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    allowScreenshots: Boolean,
    onAllowScreenshotsChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var clipboardDraft by remember { mutableStateOf("") }
    var clipboardError by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    AppListScreen("安全设置", onBack) {
        item { SettingsSwitchRow("禁止截图", allowScreenshots, onAllowScreenshotsChange) }
        item { SettingsRow("自动锁定", "$autoLockMinutes 分钟") { showAutoLockDialog = true } }
        item {
            SettingsRow(
                "生物识别解锁",
                when {
                    !biometricAvailable -> "设备不支持"
                    biometricEnabled -> "开启"
                    else -> "关闭"
                },
                if (biometricAvailable) { { onToggleBiometric(!biometricEnabled) } } else null
            )
        }
        item {
            SettingsRow("修改主密码", "打开") {
                newPassword = ""
                confirmNewPassword = ""
                changePasswordError = ""
                showChangePasswordDialog = true
            }
        }
        if (changePasswordMessage.isNotBlank()) {
            item { Text(changePasswordMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        item { SettingsHeader("剪贴板") }
        item { SettingsRow("敏感内容自动清除", clipboardClearLabel(clipboardClearSeconds)) { clipboardDraft = ""; clipboardError = ""; showClipboardDialog = true } }
    }
    if (showChangePasswordDialog) AlertDialog(
        onDismissRequest = { showChangePasswordDialog = false },
        title = { Text("修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入新的主密码。修改后请记住新密码。", style = MaterialTheme.typography.bodySmall)
                PasswordField("新主密码（4-20 个字符）", newPassword, { newPassword = it; changePasswordError = "" }, showPasswordToggle = true)
                PasswordField("再次输入新主密码", confirmNewPassword, { confirmNewPassword = it; changePasswordError = "" }, showPasswordToggle = true)
                if (changePasswordError.isNotBlank()) Text(changePasswordError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextActionButton("保存", onClick = {
                when {
                    !isMasterPasswordValid(newPassword) -> changePasswordError = "主密码长度需为 4-20 个字符"
                    newPassword != confirmNewPassword -> changePasswordError = "两次输入的主密码不一致"
                    else -> {
                        scope.launch {
                            val result = onChangeMasterPassword(newPassword)
                            if (result.isSuccess) {
                                showChangePasswordDialog = false
                                newPassword = ""
                                confirmNewPassword = ""
                                changePasswordMessage = "主密码已修改"
                            } else {
                                changePasswordError = result.exceptionOrNull()?.message ?: "主密码保存失败，请重试"
                            }
                        }
                    }
                }
            })
        },
        dismissButton = { TextActionButton("取消", onClick = { showChangePasswordDialog = false }) }
    )
    if (showAutoLockDialog) ChoiceDialog(
        "自动锁定",
        listOf(1, 5, 10, 30).map { minutes -> "${minutes} 分钟" to { onAutoLockChange(minutes) } }
    ) { showAutoLockDialog = false }
    if (showClipboardDialog) AlertDialog(
        onDismissRequest = { showClipboardDialog = false },
        title = { Text("剪贴板清除时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0 to "关闭自动清除", 15 to "15 秒", 30 to "30 秒", 60 to "60 秒").forEach { (seconds, label) ->
                    TextActionButton(label, onClick = { onClipboardClearChange(seconds); showClipboardDialog = false }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = clipboardDraft,
                    onValueChange = { clipboardDraft = it.filter(Char::isDigit); clipboardError = "" },
                    label = { Text("自定义秒数（1-86400）") },
                    singleLine = true
                )
                if (clipboardError.isNotBlank()) Text(clipboardError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextActionButton("保存", onClick = {
                val seconds = clipboardDraft.toIntOrNull()
                if (seconds == null || seconds !in 1..86_400) clipboardError = "请输入 1-86400 之间的整数"
                else { onClipboardClearChange(seconds); showClipboardDialog = false }
            })
        },
        dismissButton = { TextActionButton("取消", onClick = { showClipboardDialog = false }) }
    )
}

internal fun clipboardClearLabel(seconds: Int): String =
    if (seconds <= 0) "关闭" else "$seconds 秒"

@Preview(showBackground = true)
@Composable
private fun SecuritySettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SecuritySettingsScreen(
            biometricEnabled = false,
            biometricAvailable = true,
            onToggleBiometric = {},
            onChangeMasterPassword = { Result.success(Unit) },
            autoLockMinutes = 5,
            onAutoLockChange = {},
            clipboardClearSeconds = 30,
            onClipboardClearChange = {},
            allowScreenshots = false,
            onAllowScreenshotsChange = {},
            onBack = {}
        )
    }
}
