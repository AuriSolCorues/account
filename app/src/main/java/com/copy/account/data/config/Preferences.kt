package com.copy.account.data.config

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.copy.account.data.model.SavedTheme
import com.copy.account.data.model.vaultJson
import kotlinx.serialization.encodeToString

internal val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
internal val BIOMETRIC_SETTING = booleanPreferencesKey("biometric_enabled")
internal val AUTOFILL_SETTING = booleanPreferencesKey("autofill_enabled")
internal val KDF_ITERATIONS_SETTING = intPreferencesKey("kdf_iterations")
internal val AUTO_LOCK_SETTING = intPreferencesKey("auto_lock_seconds")
internal val BACKGROUND_LOCK_SETTING = intPreferencesKey("background_lock_seconds")
internal val LEGACY_AUTO_LOCK_MINUTES_SETTING = intPreferencesKey("auto_lock_minutes")
internal val THEME_MODE_SETTING = stringPreferencesKey("theme_mode")
internal val ACCENT_THEME_SETTING = stringPreferencesKey("accent_theme")
internal val LANGUAGE_TAG_SETTING = stringPreferencesKey("language_tag")
internal val CUSTOM_THEME_JSON_SETTING = stringPreferencesKey("custom_theme_json")
internal val CUSTOM_THEMES_SETTING = stringPreferencesKey("custom_themes")
internal val CLIPBOARD_CLEAR_SETTING = intPreferencesKey("clipboard_clear_seconds")
internal val ALLOW_SCREENSHOTS_SETTING = booleanPreferencesKey("allow_screenshots")
internal val BACKUP_TREE_URI_SETTING = stringPreferencesKey("backup_tree_uri")
internal val BACKUP_LOCATION_SETTING = stringPreferencesKey("backup_location")
internal val BACKUP_DIRECTORY_EXPANDED_SETTING = booleanPreferencesKey("backup_directory_expanded")
internal val BACKUP_FOLDER_SETTING = stringPreferencesKey("backup_folder")
internal val BACKUP_NAME_TAG_SETTING = stringPreferencesKey("backup_name_tag")

internal fun decodeSavedThemes(raw: String): List<SavedTheme> = runCatching {
    vaultJson.decodeFromString<List<SavedTheme>>(raw)
}.getOrDefault(emptyList())
internal fun encodeSavedThemes(themes: List<SavedTheme>): String =
    vaultJson.encodeToString(themes)
