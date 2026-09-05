/**
 * 职责：appsettings.json 外挂配置——只读覆盖层。启动不读、App 不写，
 *       仅设置页手动「重新加载配置文件」时加载一次；文件缺失或解析失败 → null，静默回退真值。
 * 架构位置：SettingsScreen 触发 → loadAppSettingsOverride 读文件 → applyOverride 与
 *           DataStore 真值（data/config/Preferences.kt）逐字段合并出最终生效的 AppSettings。
 * Python 类比：三层合并 ≈ 默认值 dict 被配置文件覆盖——override 只写想覆盖的键；
 *           String? 类型即 Optional[str]，?: （elvis）≈ x if x is not None else default。
 */
package com.copy.account.data.config

import android.content.Context
import com.copy.account.data.model.AppSettings
import com.copy.account.ui.theme.SavedTheme
import com.copy.account.ui.theme.stripJsonComments
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** appsettings.json 外挂覆盖：只写想覆盖的键，缺省键保持 DataStore 原值。 */
@Serializable
data class AppSettingsOverride(
    val maskChar: String? = null,
    val themeMode: String? = null,
    val accentTheme: String? = null,
    val languageTag: String? = null,
    val customThemeJson: String? = null,
    val customThemes: List<SavedTheme>? = null,
    val clipboardClearSeconds: Int? = null,
    val allowScreenshots: Boolean? = null,
    val biometricEnabled: Boolean? = null,
    val autoLockMinutes: Int? = null
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/**
 * 读取外挂配置文件（与 vault.bin 同目录）。文件缺失或解析失败 → null，App 照常走 DataStore。
 * 只有设置页手动「重新加载配置文件」才调用，启动不主动读。App 不写这个文件，只读。
 */
fun loadAppSettingsOverride(context: Context): AppSettingsOverride? {
    val file = File(context.filesDir, "appsettings.json")
    if (!file.exists()) return null
    return runCatching {
        json.decodeFromString<AppSettingsOverride>(stripJsonComments(file.readText()))
    }.getOrNull()
}

/** 逐字段合并：override 里的非 null 字段覆盖 base，其余保持 base。 */
internal fun applyOverride(base: AppSettings, override: AppSettingsOverride?): AppSettings {
    if (override == null) return base
    // 每个字段同一模式：override 里非 null 就用它，否则保留 base——即「只覆盖写了的键」。
    return base.copy(
        maskChar = override.maskChar ?: base.maskChar,
        themeMode = override.themeMode ?: base.themeMode,
        accentTheme = override.accentTheme ?: base.accentTheme,
        languageTag = override.languageTag ?: base.languageTag,
        customThemeJson = override.customThemeJson ?: base.customThemeJson,
        customThemes = override.customThemes ?: base.customThemes,
        clipboardClearSeconds = override.clipboardClearSeconds ?: base.clipboardClearSeconds,
        allowScreenshots = override.allowScreenshots ?: base.allowScreenshots,
        biometricEnabled = override.biometricEnabled ?: base.biometricEnabled,
        autoLockMinutes = override.autoLockMinutes ?: base.autoLockMinutes
    )
}
