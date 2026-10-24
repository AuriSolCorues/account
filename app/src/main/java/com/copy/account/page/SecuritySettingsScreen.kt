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
    /** 自动填充总开关：解锁时灌 AutofillBridge、关开关撤桥；填充动作本身仍需逐次生物识别。 */
    autofillEnabled: Boolean,
    onAutofillEnabledChange: (Boolean) -> Unit,
    /** 手动关闭填充窗口（撤桥）：窗口随锁定/后台自动关闭，开关行负责开与关两个方向。 */
    onCloseAutofillWindow: () -> Unit,
    /** 手动开启填充窗口（灌桥，需已解锁）：会话型开关的开启方向。 */
    onOpenAutofillWindow: () -> Unit,
    /** 填充窗口当前是否开启（AccountApp 读桥后下发；页面不直接依赖 runtime 层）。 */
    autofillWindowOpen: Boolean,
    autoLockSeconds: Int,
    onAutoLockChange: (Int) -> Unit,
    /** 切后台锁定延迟（秒，0 = 立即）；与自动锁定相互独立。 */
    backgroundLockSeconds: Int,
    onBackgroundLockChange: (Int) -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    /** PBKDF2 迭代数目标值（设置项）；改动后经密码解锁/改密透明生效。 */
    kdfIterations: Int,
    onKdfIterationsChange: (Int) -> Unit,
    /** 是否禁止截图（true=禁止，即加 FLAG_SECURE）；与 AppSettings.allowScreenshots（是否允许）互为反相。 */
    blockScreenshots: Boolean,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    /** 本机通行密钥列表（AccountApp 读注册表下发；页面不直接依赖 Context 文件 IO）。 */
    passkeyEntries: List<PasskeyDisplayEntry>,
    /** 删除本机通行密钥（AccountApp 同步删 vault 账号段密钥 + 注册表条目）。 */
    onDeletePasskey: (String) -> Unit,
    /** 设备是否支持通行密钥提供方（API≥34）；false 时区块替换为引导文案。 */
    passkeySupported: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showBackgroundLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    /** 自动填充组说明的展开态（? toggle；离页随组合销毁自动收起）。 */
    var autofillHelpExpanded by remember { mutableStateOf(false) }
    /** 填充窗口说明的展开态（会话语义单独讲，与总开关说明互不干扰）。 */
    var windowHelpExpanded by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    /** 待删除的通行密钥 credentialId；非 null 时弹确认框。 */
    var showDeletePasskeyFor by remember { mutableStateOf<String?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    AppListScreen("安全设置", onBack) {
        // ===== 隐私与锁定：应用内防偷窥姿态 + 三个安全倒计时（禁止截图/自动锁/后台锁/剪贴板）=====
        item { SettingsHeader("隐私与锁定") }
        item { SettingsSwitchRow("禁止截图", blockScreenshots, onBlockScreenshotsChange) }
        item { SettingsRow("自动锁定", autoLockLabel(autoLockSeconds)) { showAutoLockDialog = true } }
        item { SettingsRow("后台锁定", backgroundLockLabel(backgroundLockSeconds)) { showBackgroundLockDialog = true } }
        item { SettingsRow("剪贴板自动清除", clipboardClearLabel(clipboardClearSeconds)) { showClipboardDialog = true } }
        // ===== 解锁：进门与钥匙（生物识别/主密码/KDF 强度）=====
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
        // ===== 自动填充区：总开关（? 展开/收起说明）+ 会话型窗口开关 + 系统填充服务跳转 =====
        item { SettingsHeader("自动填充") }
        item {
            // 总开关行内联：? 紧贴标题后（卡片内），点 ? 只 toggle 说明不碰开关；
            // 不用 SettingsSwitchRow 是因为「点卡片空白=切开关」的误触风险（开关行语义专属）。
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
                // 总开关说明：闸的角色（开窗总闸 + 关闸撤桥）；填充时机的细节在填充窗口自己的 ? 里。
                Text(
                    "本开关是填充总闸：关闭即断开填充并清除数据；开启后还需在下面打开填充窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            // 会话型窗口开关：锁定即弹回关（不持久化）——「我正在解锁状态下允许跨应用填充」
            // 的会话语义，不是用户偏好；锁定态本页不可达，autofillEnabled 是开窗总闸。
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
                // 窗口说明：会话语义（解锁即开、锁定即关）+ 逐次验证 + 生效前提。
                Text(
                    "解锁即开、锁定即关（不跨会话记住）；锁定时在其他应用点选账号卡，" +
                        "验证指纹或主密码后单次填充。需总开关与系统填充服务均已开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { AutofillJumpRow() }
        // ===== 通行密钥区：设备支持（API≥34）才显示；列表 + 逐条删除 =====
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
    // 删除通行密钥确认弹窗：文案强调不可逆（网站侧不会自动知晓，需自行到网站移除）
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
    // 内部单位与编辑单位都是秒（v58 起）：短档 15/30 秒直达，自定义范围 1-86400 秒直传，边界无换算。
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
    // KDF 迭代数弹窗：改动立即落盘，但对已有库的效果在下次密码解锁/改密时透明生效（生物识别不触发）。
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

/**
 * 自动锁定副标题：入参与显示都是秒（v58 起编辑单位改秒级，短档 15/30 秒所见即所得）；0 表示关闭。
 */
internal fun autoLockLabel(seconds: Int): String = when {
    seconds <= 0 -> "关闭"
    else -> "$seconds 秒"
}

/** 后台锁定副标题：0 = 立即（默认，安全姿态）；>0 延迟秒数。 */
internal fun backgroundLockLabel(seconds: Int): String = when {
    seconds <= 0 -> "立即"
    seconds < 60 -> "${seconds} 秒"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "${seconds} 秒"
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
 * 说明文案已上移到组头「自动填充 ?」的展开段（窗口语义+验证+关闭方式一段讲完），此处不再重复。
 */
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

/**
 * 通行密钥列表条目的展示模型（页面解耦层）：AccountApp 从 PasskeyRegistry 读原始条目后
 * 映射成此模型下发（accountId 已换成账号显示名）；页面不依赖 Context/文件 IO。
 */
internal data class PasskeyDisplayEntry(
    val credentialId: String,
    val rpId: String,
    val accountName: String,
    val createdAt: Long
)
