package com.copy.account.data.config

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.copy.account.security.vaultJson
import com.copy.account.ui.theme.SavedTheme
import kotlinx.serialization.encodeToString

internal val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
internal val BIOMETRIC_SETTING = booleanPreferencesKey("biometric_enabled")
internal val AUTO_LOCK_SETTING = intPreferencesKey("auto_lock_minutes")
internal val THEME_MODE_SETTING = stringPreferencesKey("theme_mode")
internal val ACCENT_THEME_SETTING = stringPreferencesKey("accent_theme")
internal val LANGUAGE_TAG_SETTING = stringPreferencesKey("language_tag")
internal val CUSTOM_THEME_JSON_SETTING = stringPreferencesKey("custom_theme_json")
internal val CUSTOM_THEMES_SETTING = stringPreferencesKey("custom_themes")
internal val CLIPBOARD_CLEAR_SETTING = intPreferencesKey("clipboard_clear_seconds")
internal val ALLOW_SCREENSHOTS_SETTING = booleanPreferencesKey("allow_screenshots")
/** 用户授权的文件树 URI；实际备份目录固定为其下的 backups/account。 */
internal val BACKUP_TREE_URI_SETTING = stringPreferencesKey("backup_tree_uri")

/** 自定义主题列表单独保存，密码库本身不包含界面偏好。 */
internal fun decodeSavedThemes(raw: String): List<SavedTheme> = runCatching {
    vaultJson.decodeFromString<List<SavedTheme>>(raw)
}.getOrDefault(emptyList())

internal fun encodeSavedThemes(themes: List<SavedTheme>): String =
    vaultJson.encodeToString(themes)
