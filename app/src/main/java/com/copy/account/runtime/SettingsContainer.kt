package com.copy.account.runtime

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import com.copy.account.BuildConfig
import com.copy.account.data.config.ACCENT_THEME_SETTING
import com.copy.account.data.config.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.data.config.AUTO_LOCK_SETTING
import com.copy.account.data.config.BACKGROUND_LOCK_SETTING
import com.copy.account.data.config.LEGACY_AUTO_LOCK_MINUTES_SETTING
import com.copy.account.data.config.AppSettingsOverride
import com.copy.account.data.config.BIOMETRIC_SETTING
import com.copy.account.data.config.AUTOFILL_SETTING
import com.copy.account.data.config.CLIPBOARD_CLEAR_SETTING
import com.copy.account.data.config.CUSTOM_THEME_JSON_SETTING
import com.copy.account.data.config.CUSTOM_THEMES_SETTING
import com.copy.account.data.config.KDF_ITERATIONS_SETTING
import com.copy.account.data.config.LANGUAGE_TAG_SETTING
import com.copy.account.data.config.THEME_MODE_SETTING
import com.copy.account.data.config.applyOverride
import com.copy.account.data.config.decodeSavedThemes
import com.copy.account.data.config.encodeSavedThemes
import com.copy.account.data.config.loadAppSettingsOverride
import com.copy.account.data.config.loadSafAppSettingsOverride
import com.copy.account.data.config.settingsDataStore
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.security.DEFAULT_PASSWORD_ITERATIONS
import com.copy.account.security.MAX_PASSWORD_ITERATIONS
import com.copy.account.security.MIN_PASSWORD_ITERATIONS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

internal class SettingsContainer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onThemeApplied: (AppSettings) -> Unit
) {
    var baseSettings by mutableStateOf(AppSettings())
        private set
    var appSettingsOverride by mutableStateOf<AppSettingsOverride?>(null)
        private set
    val settings: AppSettings
        get() = applyOverride(baseSettings, appSettingsOverride)
    val maskChar: Char
        get() = settings.maskChar.firstOrNull() ?: '•'

    suspend fun loadInitial() {
        val values = context.settingsDataStore.data.first()
        val loadedSettings = AppSettings(
            biometricEnabled = values[BIOMETRIC_SETTING] ?: false,
            autofillEnabled = values[AUTOFILL_SETTING] ?: false,
            autoLockSeconds = values[AUTO_LOCK_SETTING]
                ?: ((values[LEGACY_AUTO_LOCK_MINUTES_SETTING] ?: 1) * 60).coerceIn(0, MAX_AUTO_LOCK_SECONDS),
            backgroundLockSeconds = (values[BACKGROUND_LOCK_SETTING] ?: 5).coerceIn(0, 300),
            themeMode = values[THEME_MODE_SETTING] ?: BuildConfig.DEFAULT_THEME_MODE,
            accentTheme = values[ACCENT_THEME_SETTING] ?: "green",
            languageTag = values[LANGUAGE_TAG_SETTING] ?: "zh-CN",
            customThemeJson = values[CUSTOM_THEME_JSON_SETTING] ?: "",
            customThemes = decodeSavedThemes(values[CUSTOM_THEMES_SETTING].orEmpty()),
            clipboardClearSeconds = (values[CLIPBOARD_CLEAR_SETTING] ?: 30).coerceIn(0, 86_400),
            allowScreenshots = values[ALLOW_SCREENSHOTS_SETTING] ?: false,
            kdfIterations = (values[KDF_ITERATIONS_SETTING] ?: DEFAULT_PASSWORD_ITERATIONS)
                .coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)
        )
        baseSettings = loadedSettings
        onThemeApplied(baseSettings)
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        baseSettings = transform(baseSettings)
        persist(baseSettings)
    }

    private fun persist(value: AppSettings) {
        scope.launch {
            context.settingsDataStore.edit {
                it[BIOMETRIC_SETTING] = value.biometricEnabled
                it[AUTOFILL_SETTING] = value.autofillEnabled
                it[AUTO_LOCK_SETTING] = value.autoLockSeconds
                it[BACKGROUND_LOCK_SETTING] = value.backgroundLockSeconds
                it[THEME_MODE_SETTING] = value.themeMode
                it[ACCENT_THEME_SETTING] = value.accentTheme
                it[LANGUAGE_TAG_SETTING] = value.languageTag
                it[CUSTOM_THEME_JSON_SETTING] = value.customThemeJson
                it[CUSTOM_THEMES_SETTING] = encodeSavedThemes(value.customThemes)
                it[CLIPBOARD_CLEAR_SETTING] = value.clipboardClearSeconds.coerceIn(0, 86_400)
                it[ALLOW_SCREENSHOTS_SETTING] = value.allowScreenshots
                it[KDF_ITERATIONS_SETTING] = value.kdfIterations.coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)
            }
        }
    }

    fun reload(directBackup: Boolean, backupTreeUri: String?, backupFolder: String) {
        val override = if (directBackup) {
            loadAppSettingsOverride(File(Environment.getExternalStorageDirectory(), backupFolder))
        } else {
            val tree = backupTreeUri?.let(Uri::parse)
            if (tree == null) null else loadSafAppSettingsOverride(context, tree, backupFolder)
        }
        appSettingsOverride = override
        val effective = applyOverride(baseSettings, override)
        onThemeApplied(effective)
    }
}

@Composable
internal fun rememberSettingsContainer(
    onThemeApplied: (AppSettings) -> Unit = {}
): SettingsContainer {
    val currentOnThemeApplied by rememberUpdatedState(onThemeApplied)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember {
        SettingsContainer(
            context = context,
            scope = scope,
            onThemeApplied = { currentOnThemeApplied(it) }
        )
    }
    LaunchedEffect(Unit) { container.loadInitial() }
    return container
}
