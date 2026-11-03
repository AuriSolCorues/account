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
    val kdfIterations: Int? = null,
    val biometricEnabled: Boolean? = null,
    val autoLockSeconds: Int? = null,
    val autoLockMinutes: Int? = null,
    val backgroundLockSeconds: Int? = null
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

internal const val APP_SETTINGS_FILE_NAME = "appsettings.json"

private fun parseOverride(text: String): AppSettingsOverride? =
    runCatching { json.decodeFromString<AppSettingsOverride>(stripJsonComments(text)) }.getOrNull()

fun loadAppSettingsOverride(directory: File): AppSettingsOverride? {
    val file = File(directory, APP_SETTINGS_FILE_NAME)
    if (!file.exists()) return null
    return parseOverride(file.readText())
}

fun loadSafAppSettingsOverride(
    context: Context,
    treeUri: Uri,
    folder: String
): AppSettingsOverride? {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val file = findFileInFolder(root, folder, APP_SETTINGS_FILE_NAME) ?: return null
    return runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            parseOverride(input.readBytes().decodeToString())
        }
    }.getOrNull()
}

internal fun applyOverride(base: AppSettings, override: AppSettingsOverride?): AppSettings {
    if (override == null) return base
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
        autoLockSeconds = override.autoLockSeconds ?: (override.autoLockMinutes?.let { it * 60 }) ?: base.autoLockSeconds,
        backgroundLockSeconds = override.backgroundLockSeconds ?: base.backgroundLockSeconds,
        kdfIterations = override.kdfIterations ?: base.kdfIterations
    )
}
