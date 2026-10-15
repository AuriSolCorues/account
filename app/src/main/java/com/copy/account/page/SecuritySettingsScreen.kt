/**
 * 职责：安全设置二级页——禁止截图、自动锁定、生物识别、自动填充（开关 + 系统服务跳转）、
 *       修改主密码，以及剪贴板敏感内容自动清除。
 * 架构位置：AccountApp 的 AppPage.SecuritySettings 分支（由设置中枢进入）；行组件来自
 *           ui/components/Rows.kt，时长弹窗复用 ui/components/Dialogs.kt 的 NumberSettingDialog。
 * Python 类比：从原大设置页里切出的一块——≈ 把一个超长函数按职责拆成几个同名职责的小函数，
 *           数据与事件仍全部由 AccountApp 经 props 进出。
 */
package com.copy.account.page

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.security.MASTER_PASSWORD_RULE_HINT
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.NumberSettingDialog
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
    /** 自动填充总开关：解锁时灌 AutofillBridge、关开关撤桥；填充动作本身仍需逐次生物识别。 */
    autofillEnabled: Boolean,
    onAutofillEnabledChange: (Boolean) -> Unit,
    /** 手动关闭填充窗口（撤桥）：窗口随锁定/后台自动关闭，本行是唯一主动关闭入口。 */
    onCloseAutofillWindow: () -> Unit,
    /** 填充窗口当前是否开启（AccountApp 读桥后下发；页面不直接依赖 runtime 层）。 */
    autofillWindowOpen: Boolean,
    autoLockSeconds: Int,
    onAutoLockChange: (Int) -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    /** 是否禁止截图（true=禁止，即加 FLAG_SECURE）；与 AppSettings.allowScreenshots（是否允许）互为反相。 */
    blockScreenshots: Boolean,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    AppListScreen("安全设置", onBack) {
        item { SettingsSwitchRow("禁止截图", blockScreenshots, onBlockScreenshotsChange) }
        item { SettingsRow("自动锁定", autoLockLabel(autoLockSeconds)) { showAutoLockDialog = true } }
        item {
            SettingsSwitchRow(
                "生物识别解锁",
                biometricEnabled,
                onToggleBiometric,
                enabled = biometricAvailable
            )
        }
        // ===== 自动填充区：总开关（开窗闸）+ 窗口状态行（含说明）+ 系统填充服务跳转 =====
        item { SettingsHeader("自动填充") }
        item { SettingsSwitchRow("自动填充", autofillEnabled, onAutofillEnabledChange) }
        item {
            Column {
                SettingsRow(
                    "填充窗口",
                    if (autofillWindowOpen) "开启中" else "已关闭",
                    if (autofillWindowOpen) { { onCloseAutofillWindow() } } else null
                )
                Text(
                    "解锁后自动开启，锁定或退出本应用即关闭。" +
                        "锁定状态下在其他应用点选账号卡并验证指纹/主密码，即可单次填充。" +
                        "点按「开启中」可立即断开并清除数据，下次解锁自动重开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item { AutofillJumpRow() }
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
        item { SettingsRow("敏感内容自动清除", clipboardClearLabel(clipboardClearSeconds)) { showClipboardDialog = true } }
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
    // 内部单位是秒（autoLockSeconds），弹窗仍以分钟编辑——只在回调边界 ×60 换算，用户感知不变。
    if (showAutoLockDialog) NumberSettingDialog(
        title = "自动锁定",
        unit = "分钟",
        minValue = 1,
        maxValue = MAX_AUTO_LOCK_SECONDS / 60,
        presets = listOf(
            "关闭" to { onAutoLockChange(0) },
            "1 分钟" to { onAutoLockChange(60) },
            "5 分钟" to { onAutoLockChange(300) },
            "10 分钟" to { onAutoLockChange(600) },
            "30 分钟" to { onAutoLockChange(1800) }
        ),
        onConfirm = { minutes -> onAutoLockChange(minutes.coerceIn(0, MAX_AUTO_LOCK_SECONDS / 60) * 60) },
        onDismiss = { showAutoLockDialog = false }
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
}

/**
 * 自动锁定副标题：入参为内部的秒值，显示仍以分钟呈现（0 表示关闭；整小时比“120 分钟”好读）。
 */
internal fun autoLockLabel(seconds: Int): String = when {
    seconds <= 0 -> "关闭"
    else -> autoLockMinutesLabel(seconds / 60)
}

private fun autoLockMinutesLabel(minutes: Int): String = when {
    minutes < 1 -> "<1 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}

/**
 * 剪贴板清除副标题：0 表示关闭。
 * 加“小时”层是因为上限 86400 秒，只按秒显示会变成 86400 秒这种没法读的数字。
 */
internal fun clipboardClearLabel(seconds: Int): String =
    when {
        seconds <= 0 -> "关闭"
        seconds < 60 -> "$seconds 秒"
        seconds % 3600 == 0 -> "${seconds / 3600} 小时"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }

/**
 * 自动填充区的跳转行：点击拉起系统的「设为填充服务」确认页（部分机型无此 action，静默吞掉）。
 * 独立小节下方附说明文案：开关只是本应用的闸（开窗时机在解锁/自愈），填充时机、逐次验证
 * 与窗口关闭方式一并写明，避免用户开了开关却不懂窗口何时生效、如何收回。
 */
@Composable
private fun AutofillJumpRow() {
    val context = LocalContext.current
    Column {
        SettingsRow("跳转系统填充服务设置", "前往") {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Exception) {
            }
        }
        Text(
            "开启后即可在其他应用中填充；每次填充仍需指纹/锁屏验证（锁定状态下验证主密码或指纹）。" +
                "窗口关闭方式：本行手动关闭、锁定/退出应用自动关闭，或关闭自动填充开关。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SecuritySettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SecuritySettingsScreen(
            biometricEnabled = false,
            biometricAvailable = true,
            onToggleBiometric = {},
            onChangeMasterPassword = { Result.success(Unit) },
            autofillEnabled = false,
            autofillWindowOpen = false,
            onAutofillEnabledChange = {},
            onCloseAutofillWindow = {},
            autoLockSeconds = 300,
            onAutoLockChange = {},
            clipboardClearSeconds = 30,
            onClipboardClearChange = {},
            blockScreenshots = false,
            onBlockScreenshotsChange = {},
            onBack = {}
        )
    }
}
