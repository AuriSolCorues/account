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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
