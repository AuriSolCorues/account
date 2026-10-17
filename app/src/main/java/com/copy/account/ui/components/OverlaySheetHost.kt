/**
 * 职责：透明浮层 Activity（PasskeyCreate/Get、AutofillAuth）专用的 Compose 接入点——
 *       从 DataStore 一次性读主题三键（theme_mode/accent_theme/custom_theme_json）套
 *       AccountTheme，再渲染主界面同款的 AppBottomSheet 弹层。三个浮层的手搭 View 硬卡
 *       （硬编码白/暗底 LinearLayout）统一由这里接管，浮层视觉与主界面面板完全一致。
 * 架构位置：ui/components 通用层；service/ 的浮层 Activity 在 onCreate 里
 *           setContent { OverlayThemeHost { AppBottomSheet(...) { ... } } }。
 *           主题键缺省值与 SettingsContainer.loadInitial 同源（green/跟随系统）。
 * Python 类比：≈ 一个把「读配置 → 套皮肤 → 渲染面板」打包的小装置——Activity 只管
 *           塞内容和回调，配色/明暗/圆角全部由主主题栈接管。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.data.config.ACCENT_THEME_SETTING
import com.copy.account.data.config.CUSTOM_THEME_JSON_SETTING
import com.copy.account.data.config.THEME_MODE_SETTING
import com.copy.account.data.config.settingsDataStore
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.first

/**
 * 浮层主题宿主：读主题三键 → AccountTheme → content。
 * produceState 首帧用默认值（green/跟随系统）渲染，DataStore 读到后重组刷新——
 * 浮层生命周期极短，正常情况下首帧即终态（DataStore 读取是内存级毫秒级）。
 * 一次性读（first()）不持续订阅：浮层活着的几秒内设置不会变，订阅无意义。
 */
@Composable
fun OverlayThemeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val stored by produceState(Triple(BuildDefaults.themeMode, BuildDefaults.accent, BuildDefaults.customJson)) {
        value = runCatching {
            val v = context.settingsDataStore.data.first()
            Triple(
                v[THEME_MODE_SETTING] ?: BuildDefaults.themeMode,
                v[ACCENT_THEME_SETTING] ?: BuildDefaults.accent,
                v[CUSTOM_THEME_JSON_SETTING] ?: BuildDefaults.customJson
            )
        }.getOrDefault(BuildDefaults.triple)
    }
    val (themeMode, accentTheme, customThemeJson) = stored
    AccountTheme(
        darkTheme = resolveDarkTheme(themeMode, androidx.compose.foundation.isSystemInDarkTheme()),
        accentTheme = accentTheme,
        customThemeJson = customThemeJson,
        content = content
    )
}

/** 与 MainActivity 冷启动初值同源的缺省三键（BuildConfig 默认 + green + 空 JSON）。 */
private object BuildDefaults {
    val themeMode: String get() = com.copy.account.BuildConfig.DEFAULT_THEME_MODE
    const val accent: String = "green"
    const val customJson: String = ""
    val triple: Triple<String, String, String>
        get() = Triple(themeMode, accent, customJson)
}

// ==================== 浮层弹层内容组件 ====================
// 抽成独立 @Composable 的目的：Compose Preview 只扫 ui/ 下的组件函数，Activity 内联代码
// 看不到——抽出来后 IDE Split/Design 视图即可直查成品（配文件尾 @Preview）。

/** 弹层内容公共骨架：标题行 + 说明文 + 底部「取消 / 主操作」按钮行。 */
@Composable
internal fun OverlaySheetItems(
    title: String,
    headline: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitleRow(title)
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        extraContent()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextActionButton("取消", onClick = onCancel, modifier = Modifier.weight(1f))
            PrimaryButton(confirmText, onClick = onConfirm, modifier = Modifier.weight(2f))
        }
    }
}

/** 注册确认内容：网站域 + 用户名 + 私钥随库说明。 */
@Composable
internal fun PasskeyCreateSheetItems(rpId: String, userName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    OverlaySheetItems(
        title = "通行密钥",
        headline = "为 $rpId 创建通行密钥",
        description = "用户名：${userName.ifEmpty { "（网站未提供）" }}\n私钥随加密库保存（.acc 备份可恢复）",
        confirmText = "创建通行密钥",
        onConfirm = onConfirm,
        onCancel = onCancel
    )
}

/**
 * 通行密钥登录密码验证内容（无指纹设备的兜底路径）：
 * 系统验证框在无指纹设备弹的是「设备锁屏凭据」，DEK 仍需本应用主密码派生——
 * 系统框通过后落到这里输主密码。状态自持（文本/提交中锁按钮）。
 */
@Composable
internal fun PasskeyPasswordSheetItems(rpId: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    OverlaySheetItems(
        title = "通行密钥",
        headline = "验证主密码以登录 $rpId",
        description = "本设备未配置指纹，输入主密码完成验证",
        confirmText = "验证并继续",
        onConfirm = {
            if (!submitting && pwd.isNotBlank()) {
                submitting = true
                onConfirm(pwd)
            }
        },
        onCancel = onCancel,
        extraContent = {
            PasswordField("主密码", pwd, { pwd = it }, showPasswordToggle = true)
        }
    )
}

/**
 * 自动填充密码验证弹层内容：主密码框 +「取消 / 验证并填充」。
 * 状态自持（文本/验证中），验证走 onConfirm(密码)——由 Activity 在后台线程执行，
 * 返回后本组件经 submitting 态锁按钮；失败由 Activity 弹 Toast，这里只复位。
 * 注：onConfirm 为同步回调（内部自带 Thread 的场景由调用方保证时序），复用无状态约束。
 */
@Composable
internal fun AutofillPasswordSheetItems(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    OverlaySheetItems(
        title = "验证主密码",
        headline = "验证后向目标应用填充所选账号",
        description = "本设备未配置指纹，输入主密码完成验证",
        confirmText = "验证并填充",
        onConfirm = {
            if (!submitting) {
                submitting = true
                onConfirm(pwd)
            }
        },
        onCancel = onCancel,
        extraContent = {
            PasswordField("主密码", pwd, { pwd = it }, showPasswordToggle = true)
        }
    )
}

// ==================== IDE 预览 ====================
// SheetPagePreview：ModalBottomSheet 在预览窗不弹层，此件把内容画成「遮罩 + 底部圆角面板」，
// Design/Split 视图即查成品（Sheet.kt 先例）。
// darkTheme 显式直写（预览环境 isSystemInDarkTheme 不可靠，== "system" 判跟随系统的旧写法
// 在默认值改为 system 后恒真→预览恒暗）；配色按 resolveTheme 全路径铺 5 组：
// 绿明/绿暗（内置默认）、蓝明/蓝暗（accentTheme）、自定义 JSON 暗（customThemeJson 分支）。

/** 预览配色组：覆盖 resolveTheme 的全部代码路径（自定义明与暗同一解析分支，铺暗即可）。 */
private val PreviewThemeMatrix: List<Pair<String, Triple<Boolean, String, String>>> = listOf(
    "绿·明" to Triple(false, "green", ""),
    "绿·暗" to Triple(true, "green", ""),
    "蓝·明" to Triple(false, "blue", ""),
    "蓝·暗" to Triple(true, "blue", ""),
    "自定义·暗" to Triple(true, "green", SAMPLE_CUSTOM_THEME_JSON)
)

/** 自定义主题样例（19 色槽位；暖橙深色，验证 customThemeJson 解析分支与弹层观感）。 */
private const val SAMPLE_CUSTOM_THEME_JSON =
    """{"topBar":"#2A2118","topBarText":"#F5E7D5","background":"#221B13","surface":"#2E2419",
       "surfaceAlt":"#3A2E20","primary":"#E8A34D","primaryText":"#1A1208","selectedBackground":"#4A3618",
       "text":"#F0E2CC","textMuted":"#A89578","divider":"#4A3D2B","inputBackground":"#332918",
       "inputBorder":"#5A4A32","icon":"#D8C4A4","danger":"#E86A5A","warning":"#E2B34E",
       "success":"#8FBC6A","disabled":"#6A5C48","overlay":"#99000000"}"""

@Composable
private fun ThemedSheetPreview(
    theme: Triple<Boolean, String, String>,
    heightDp: Int,
    content: @Composable () -> Unit
) {
    AccountTheme(darkTheme = theme.first, accentTheme = theme.second, customThemeJson = theme.third) {
        SheetPagePreview { content() }
    }
}

@Preview(name = "注册确认·绿明", showBackground = true, widthDp = 411, heightDp = 320)
@Composable
private fun PasskeyCreateSheetPreviewGreenLight() {
    ThemedSheetPreview(PreviewThemeMatrix[0].second, 320) {
        PasskeyCreateSheetItems("github.com", "octocat", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "注册确认·绿暗", showBackground = true, widthDp = 411, heightDp = 320)
@Composable
private fun PasskeyCreateSheetPreviewGreenDark() {
    ThemedSheetPreview(PreviewThemeMatrix[1].second, 320) {
        PasskeyCreateSheetItems("github.com", "octocat", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "注册确认·蓝明", showBackground = true, widthDp = 411, heightDp = 320)
@Composable
private fun PasskeyCreateSheetPreviewBlueLight() {
    ThemedSheetPreview(PreviewThemeMatrix[2].second, 320) {
        PasskeyCreateSheetItems("github.com", "octocat", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "注册确认·蓝暗", showBackground = true, widthDp = 411, heightDp = 320)
@Composable
private fun PasskeyCreateSheetPreviewBlueDark() {
    ThemedSheetPreview(PreviewThemeMatrix[3].second, 320) {
        PasskeyCreateSheetItems("github.com", "octocat", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "注册确认·自定义暗", showBackground = true, widthDp = 411, heightDp = 320)
@Composable
private fun PasskeyCreateSheetPreviewCustomDark() {
    ThemedSheetPreview(PreviewThemeMatrix[4].second, 320) {
        PasskeyCreateSheetItems("github.com", "octocat", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "登录密码·绿明", showBackground = true, widthDp = 411, heightDp = 380)
@Composable
private fun PasskeyPasswordSheetPreviewGreenLight() {
    ThemedSheetPreview(PreviewThemeMatrix[0].second, 380) {
        PasskeyPasswordSheetItems("github.com", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "登录密码·绿暗", showBackground = true, widthDp = 411, heightDp = 380)
@Composable
private fun PasskeyPasswordSheetPreviewGreenDark() {
    ThemedSheetPreview(PreviewThemeMatrix[1].second, 380) {
        PasskeyPasswordSheetItems("github.com", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "登录密码·蓝暗", showBackground = true, widthDp = 411, heightDp = 380)
@Composable
private fun PasskeyPasswordSheetPreviewBlueDark() {
    ThemedSheetPreview(PreviewThemeMatrix[3].second, 380) {
        PasskeyPasswordSheetItems("github.com", onConfirm = {}, onCancel = {})
    }
}

@Preview(name = "自动填充密码·绿暗", showBackground = true, widthDp = 411, heightDp = 380)
@Composable
private fun AutofillPasswordSheetPreviewGreenDark() {
    ThemedSheetPreview(PreviewThemeMatrix[1].second, 380) {
        AutofillPasswordSheetItems(onConfirm = {}, onCancel = {})
    }
}
