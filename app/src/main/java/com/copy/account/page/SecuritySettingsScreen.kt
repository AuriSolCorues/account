package com.copy.account.page

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.security.MAX_PASSWORD_ITERATIONS
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.MIN_PASSWORD_ITERATIONS
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.NumberSettingDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsHeader
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.SettingsSwitchRow
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
import kotlinx.coroutines.launch

@Composable
internal fun SecuritySettingsScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onChangeMasterPassword: suspend (String) -> Result<Unit>,
    autofillEnabled: Boolean,
    onAutofillEnabledChange: (Boolean) -> Unit,
    onCloseAutofillWindow: () -> Unit,
    onOpenAutofillWindow: () -> Unit,
    autofillWindowOpen: Boolean,
    autoLockSeconds: Int,
    onAutoLockChange: (Int) -> Unit,
    backgroundLockSeconds: Int,
    onBackgroundLockChange: (Int) -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    kdfIterations: Int,
    onKdfIterationsChange: (Int) -> Unit,
    blockScreenshots: Boolean,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    passkeyEntries: List<PasskeyDisplayEntry>,
    onDeletePasskey: (String) -> Unit,
    passkeySupported: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showBackgroundLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var autofillHelpExpanded by remember { mutableStateOf(false) }
    var windowHelpExpanded by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showDeletePasskeyFor by remember { mutableStateOf<String?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    AppListScreen("安全设置", onBack) {
        item { SettingsHeader("隐私与锁定") }
        item { SettingsSwitchRow("禁止截图", blockScreenshots, onBlockScreenshotsChange) }
        item { SettingsRow("自动锁定", autoLockLabel(autoLockSeconds)) { showAutoLockDialog = true } }
        item { SettingsRow("后台锁定", backgroundLockLabel(backgroundLockSeconds)) { showBackgroundLockDialog = true } }
        item { SettingsRow("剪贴板自动清除", clipboardClearLabel(clipboardClearSeconds)) { showClipboardDialog = true } }
        item { SettingsHeader("解锁") }
        item {
            SettingsSwitchRow(
                "生物识别解锁",
                biometricEnabled,
                onToggleBiometric,
                enabled = biometricAvailable
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
        item { SettingsRow("PBKDF2 迭代次数", "%,d".format(kdfIterations)) { showKdfDialog = true } }
        item { SettingsHeader("自动填充") }
        item {
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动填充")
                    TextActionButton(
                        if (autofillHelpExpanded) "−" else "?",
                        onClick = { autofillHelpExpanded = !autofillHelpExpanded },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = autofillEnabled,
                        onCheckedChange = onAutofillEnabledChange
                    )
                }
            }
        }
        if (autofillHelpExpanded) {
            item {
                Text(
                    "本开关是填充总闸：关闭即断开填充并清除数据；开启后还需在下面打开填充窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("填充窗口")
                    TextActionButton(
                        if (windowHelpExpanded) "−" else "?",
                        onClick = { windowHelpExpanded = !windowHelpExpanded },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = autofillWindowOpen,
                        onCheckedChange = { open -> if (open) onOpenAutofillWindow() else onCloseAutofillWindow() },
                        enabled = autofillEnabled
                    )
                }
            }
        }
        if (windowHelpExpanded) {
            item {
                Text(
                    "解锁即开、锁定即关（不跨会话记住）；锁定时在其他应用点选账号卡，" +
                        "验证指纹或主密码后单次填充。需总开关与系统填充服务均已开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { AutofillJumpRow() }
        if (passkeySupported) {
            item { SettingsHeader("通行密钥") }
            if (passkeyEntries.isEmpty()) {
                item {
                    Text(
                        "本机还没有通行密钥。在 GitHub/Google 等网站注册通行密钥时选择「账号本子」即可保存到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(passkeyEntries, key = { it.credentialId }) { entry ->
                    Column {
                        SettingsRow(
                            entry.rpId,
                            buildString {
                                append("添加于 ")
                                append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(entry.createdAt)))
                                if (entry.accountName.isNotBlank()) append(" · ").append(entry.accountName)
                            }
                        ) { showDeletePasskeyFor = entry.credentialId }
                    }
                }
            }
        }
    }
    showDeletePasskeyFor?.let { credentialId ->
        val entry = passkeyEntries.firstOrNull { it.credentialId == credentialId }
        AlertDialog(
            onDismissRequest = { showDeletePasskeyFor = null },
            title = { Text("删除通行密钥") },
            text = { Text("删除后本机将无法再登录 ${entry?.rpId.orEmpty()} 的该账号（网站侧的通行密钥需自行到网站设置里移除）。此操作不可恢复。") },
            confirmButton = {
                TextActionButton("删除", { onDeletePasskey(credentialId); showDeletePasskeyFor = null }, textColor = MaterialTheme.colorScheme.error)
            },
            dismissButton = {
                TextActionButton("取消", { showDeletePasskeyFor = null })
            }
        )
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
                    !isMasterPasswordValid(newPassword) -> changePasswordError = MASTER_PASSWORD_RULE_HINT
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
    if (showAutoLockDialog) NumberSettingDialog(
        title = "自动锁定",
        unit = "秒",
        minValue = 1,
        maxValue = MAX_AUTO_LOCK_SECONDS,
        presets = listOf(
            "关闭" to { onAutoLockChange(0) },
            "15 秒" to { onAutoLockChange(15) },
            "30 秒" to { onAutoLockChange(30) },
            "1 分钟" to { onAutoLockChange(60) },
            "5 分钟" to { onAutoLockChange(300) },
            "10 分钟" to { onAutoLockChange(600) },
            "30 分钟" to { onAutoLockChange(1800) }
        ),
        onConfirm = { seconds -> onAutoLockChange(seconds.coerceIn(1, MAX_AUTO_LOCK_SECONDS)) },
        onDismiss = { showAutoLockDialog = false }
    )
    if (showBackgroundLockDialog) NumberSettingDialog(
        title = "后台锁定",
        unit = "秒",
        minValue = 0,
        maxValue = 300,
        presets = listOf(
            "立即" to { onBackgroundLockChange(0) },
            "5 秒" to { onBackgroundLockChange(5) },
            "10 秒" to { onBackgroundLockChange(10) },
            "15 秒" to { onBackgroundLockChange(15) },
            "30 秒" to { onBackgroundLockChange(30) }
        ),
        onConfirm = { seconds -> onBackgroundLockChange(seconds.coerceIn(0, 300)) },
        onDismiss = { showBackgroundLockDialog = false }
    )
    if (showClipboardDialog) NumberSettingDialog(
        title = "剪贴板清除时间",
        unit = "秒",
        minValue = 5,
        maxValue = 86_400,
        presets = listOf(
            "不清空" to { onClipboardClearChange(0) },
            "15 秒" to { onClipboardClearChange(15) },
            "30 秒" to { onClipboardClearChange(30) },
            "1 分钟" to { onClipboardClearChange(60) }
        ),
        onConfirm = { seconds -> onClipboardClearChange(seconds.coerceIn(0, 86_400)) },
        onDismiss = { showClipboardDialog = false }
    )
    if (showKdfDialog) NumberSettingDialog(
        title = "PBKDF2 迭代次数",
        unit = "次",
        minValue = MIN_PASSWORD_ITERATIONS,
        maxValue = MAX_PASSWORD_ITERATIONS,
        presets = listOf(
            "600,000（推荐）" to { onKdfIterationsChange(600_000) },
            "500,000" to { onKdfIterationsChange(500_000) },
            "300,000" to { onKdfIterationsChange(300_000) },
            "1,000,000" to { onKdfIterationsChange(1_000_000) }
        ),
        onConfirm = { iterations -> onKdfIterationsChange(iterations.coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)) },
        onDismiss = { showKdfDialog = false }
    )
}

internal fun autoLockLabel(seconds: Int): String = when {
    seconds <= 0 -> "关闭"
    else -> "$seconds 秒"
}

internal fun backgroundLockLabel(seconds: Int): String = when {
    seconds <= 0 -> "立即"
    seconds < 60 -> "${seconds} 秒"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "${seconds} 秒"
}

internal fun clipboardClearLabel(seconds: Int): String =
    when {
        seconds <= 0 -> "关闭"
        seconds < 60 -> "$seconds 秒"
        seconds % 3600 == 0 -> "${seconds / 3600} 小时"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }

@Composable
private fun AutofillJumpRow() {
    val context = LocalContext.current
    SettingsRow("跳转系统填充服务设置", "前往") {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
            )
        } catch (_: Exception) {
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SecuritySettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        SecuritySettingsScreen(
            biometricEnabled = false,
            biometricAvailable = true,
            onToggleBiometric = {},
            onChangeMasterPassword = { Result.success(Unit) },
            autofillEnabled = false,
            autofillWindowOpen = false,
            onAutofillEnabledChange = {},
            onCloseAutofillWindow = {},
            onOpenAutofillWindow = {},
            autoLockSeconds = 45,
            onAutoLockChange = {},
            backgroundLockSeconds = 5,
            onBackgroundLockChange = {},
            clipboardClearSeconds = 30,
            onClipboardClearChange = {},
            kdfIterations = 600_000,
            onKdfIterationsChange = {},
            blockScreenshots = false,
            onBlockScreenshotsChange = {},
            passkeyEntries = emptyList(),
            onDeletePasskey = {},
            passkeySupported = true,
            onBack = {}
        )
    }
}

internal data class PasskeyDisplayEntry(
    val credentialId: String,
    val rpId: String,
    val accountName: String,
    val createdAt: Long
)
