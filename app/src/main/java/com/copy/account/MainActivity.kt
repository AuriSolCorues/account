package com.copy.account

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.themePaletteFromJson
import androidx.compose.ui.graphics.luminance

class MainActivity : FragmentActivity() {
    internal var userInteractionSink: (() -> Unit)? = null

    override fun onUserInteraction() {
        super.onUserInteraction()
        userInteractionSink?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.copy.account.runtime.installCrashLogger(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mode = display?.supportedModes
                ?.filter { it.physicalWidth == display.mode.physicalWidth && it.physicalHeight == display.mode.physicalHeight }
                ?.maxByOrNull { it.refreshRate }
            mode?.let { window.attributes.preferredDisplayModeId = it.modeId }
        }
        setContent {
            var themeMode by remember {
                mutableStateOf(BuildConfig.DEFAULT_THEME_MODE)
            }
            var accentTheme by remember {
                mutableStateOf("green")
            }
            var customThemeJson by remember {
                mutableStateOf("")
            }
            var allowScreenshots by remember {
                mutableStateOf(false)
            }
            val darkTheme = when (themeMode) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            DisposableEffect(allowScreenshots) {
                if (allowScreenshots) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }
            SideEffect {
                val customBackground = themePaletteFromJson(customThemeJson)?.background
                val useDarkSystemIcons = customBackground?.luminance()?.let { it > 0.5f } ?: !darkTheme
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = useDarkSystemIcons
                    isAppearanceLightNavigationBars = useDarkSystemIcons
                }
            }
            AccountTheme(darkTheme = darkTheme, accentTheme = accentTheme, customThemeJson = customThemeJson) {
                Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    AccountApp(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                        },
                        accentTheme = accentTheme,
                        onAccentThemeChange = { accent ->
                            accentTheme = accent
                        },
                        customThemeJson = customThemeJson,
                        onCustomThemeJsonChange = { json ->
                            customThemeJson = json
                        },
                        allowScreenshots = allowScreenshots,
                        onAllowScreenshotsChange = { enabled ->
                            allowScreenshots = enabled
                        }
                    )
                }
            }
        }
    }
}
