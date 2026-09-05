package com.copy.account.data.config

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.copy.account.security.vaultJson
import com.copy.account.ui.theme.SavedTheme
import kotlinx.serialization.encodeToString

// 初始化应用设置 DataStore
internal val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
// 生物识别认证开关
internal val BIOMETRIC_SETTING = booleanPreferencesKey("biometric_enabled")
// 自动锁定时间（单位：分钟）
internal val AUTO_LOCK_SETTING = intPreferencesKey("auto_lock_minutes")
// 主题模式（如浅色、深色、跟随系统）
internal val THEME_MODE_SETTING = stringPreferencesKey("theme_mode")
// 强调色/主题色配置
internal val ACCENT_THEME_SETTING = stringPreferencesKey("accent_theme")
// 应用语言标签
internal val LANGUAGE_TAG_SETTING = stringPreferencesKey("language_tag")
// 单个自定义主题的 JSON 数据
internal val CUSTOM_THEME_JSON_SETTING = stringPreferencesKey("custom_theme_json")
// 自定义主题列表的 JSON 数据
internal val CUSTOM_THEMES_SETTING = stringPreferencesKey("custom_themes")
// 剪贴板自动清理时间（单位：秒）
internal val CLIPBOARD_CLEAR_SETTING = intPreferencesKey("clipboard_clear_seconds")
// 是否允许应用内截图 默认不允许
internal val ALLOW_SCREENSHOTS_SETTING = booleanPreferencesKey("allow_screenshots")
/** 用户授权的文件树 URI；实际备份目录固定为其下的 backups/account。 */
internal val BACKUP_TREE_URI_SETTING = stringPreferencesKey("backup_tree_uri")

/**
 * 将 JSON 字符串解析为自定义主题列表。
 * 自定义主题列表单独保存，密码库本身不包含界面偏好。
 * 使用 runCatching 保证解析失败时返回空列表，防止应用崩溃。
 */
internal fun decodeSavedThemes(raw: String): List<SavedTheme> = runCatching {
    vaultJson.decodeFromString<List<SavedTheme>>(raw)
}.getOrDefault(emptyList())
//将自定义主题列表序列化为 JSON 字符串，用于持久化存储。
internal fun encodeSavedThemes(themes: List<SavedTheme>): String =
    vaultJson.encodeToString(themes)
