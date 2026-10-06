/**
 * 职责：appsettings.json 外挂配置——只读覆盖层。启动不读、App 不写，
 *       仅主题与语言页手动「重新加载配置文件」时加载一次；文件缺失或解析失败 → null，静默回退真值。
 *       文件放在**备份目录**里（与 .acc 同级），用户用文件管理器就能看到并编辑。
 * 架构位置：AppearanceSettingsScreen 触发 → AccountApp 按 directBackup 分派到 loadAppSettingsOverride
 *           （直写轨）或 loadSafAppSettingsOverride（SAF 轨）→ applyOverride 与 DataStore 真值
 *           （data/config/Preferences.kt）逐字段合并出最终生效的 AppSettings。
 * Python 类比：三层合并 ≈ 默认值 dict 被配置文件覆盖——override 只写想覆盖的键；
 *           String? 类型即 Optional[str]，?: （elvis）≈ x if x is not None else default。
 */
package com.copy.account.data.config

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.copy.account.data.backup.findFileInFolder
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.SavedTheme
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

/** 移除 JSONC 的行注释和块注释，同时保留字符串里的斜杠。 */
internal fun stripJsonComments(source: String): String {
    val out = StringBuilder(source.length)
    var inString = false
    var escaped = false
    var block = false
    var line = false
    var i = 0
    // 单指针状态机，四个互斥状态：行注释内/块注释内/字符串内/普通文本。
    // 只丢弃注释字符；字符串字面量里的双斜杠与转义引号原样保留（escaped 标志防误判字符串边界）。
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        if (line) {
            if (c == '\n') { line = false; out.append(c) }
        } else if (block) {
            if (c == '*' && next == '/') { block = false; i++ }
        } else if (!inString && c == '/' && next == '/') {
            line = true; i++
        } else if (!inString && c == '/' && next == '*') {
            block = true; i++
        } else {
            out.append(c)
            if (c == '"' && !escaped) inString = !inString
            escaped = c == '\\' && !escaped
            if (c != '\\') escaped = false
        }
        i++
    }
    return out.toString()
}

/** 配置文件名；与 .acc 同级放在备份目录里，用户用文件管理器就能看到并编辑。 */
internal const val APP_SETTINGS_FILE_NAME = "appsettings.json"

/**
 * 两条轨共用的解析：剥掉注释再解 JSON，任何失败一律 null。
 * 「本次无外挂覆盖」的原因不向上区分（缺文件 / 读不到 / 写错了），上层只关心有没有覆盖。
 */
private fun parseOverride(text: String): AppSettingsOverride? =
    runCatching { json.decodeFromString<AppSettingsOverride>(stripJsonComments(text)) }.getOrNull()

/**
 * 读取外挂配置文件：传备份目录（直写轨的 externalStorageDir 或其子目录）。
 * 文件缺失或解析失败 → null，App 照常走 DataStore。只有主题与语言页手动「重新加载配置文件」
 * 才调用，启动不主动读。App 不写这个文件，只读。
 */
fun loadAppSettingsOverride(directory: File): AppSettingsOverride? {
    val file = File(directory, APP_SETTINGS_FILE_NAME)
    if (!file.exists()) return null
    return parseOverride(file.readText())
}

/**
 * SAF 轨（API<30）的同一件事：目录不存在或文件读不到都返回 null。
 * 刻意用只读的 findFileInFolder —— 不能像写路径那样顺手建目录，否则点一次「重新加载」
 * 就会凭空在授权树里造出空目录。两种失败原因在语义上等价：都是「本次无外挂覆盖」。
 */
fun loadSafAppSettingsOverride(
    context: Context,
    treeUri: Uri,
    folder: String
): AppSettingsOverride? {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val file = findFileInFolder(root, folder, APP_SETTINGS_FILE_NAME) ?: return null
    // 外层 runCatching 不能省：readBytes() 可能抛 IOException，那也属于「读不到」。
    // 内层 parseOverride 只兜 JSON 解析，两层分工不同。
    return runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            parseOverride(input.readBytes().decodeToString())
        }
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
