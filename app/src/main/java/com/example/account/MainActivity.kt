package com.example.account

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import com.example.account.ui.theme.AccountTheme
import com.example.account.ui.theme.themePaletteFromJson
import androidx.compose.ui.graphics.luminance

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind system bars so the top bar reaches the display cutout
        // without a separate white strip on fullscreen devices.
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            var themeMode by remember {
                mutableStateOf(getSharedPreferences("account_ui", MODE_PRIVATE).getString("theme_mode", "dark") ?: "dark")
            }
            var accentTheme by remember {
                mutableStateOf(getSharedPreferences("account_ui", MODE_PRIVATE).getString("accent_theme", "green") ?: "green")
            }
            var customThemeJson by remember {
                mutableStateOf(getSharedPreferences("account_ui", MODE_PRIVATE).getString("custom_theme_json", "") ?: "")
            }
            val darkTheme = when (themeMode) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            SideEffect {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                val customBackground = themePaletteFromJson(customThemeJson)?.background
                val useDarkSystemIcons = customBackground?.luminance()?.let { it > 0.5f } ?: !darkTheme
                window.decorView.systemUiVisibility = if (!useDarkSystemIcons) 0 else {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            AccountTheme(dynamicColor = false, darkTheme = darkTheme, accentTheme = accentTheme, customThemeJson = customThemeJson) {
                Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    AccountApp(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            getSharedPreferences("account_ui", MODE_PRIVATE).edit().putString("theme_mode", mode).apply()
                        },
                        accentTheme = accentTheme,
                        onAccentThemeChange = { accent ->
                            accentTheme = accent
                            getSharedPreferences("account_ui", MODE_PRIVATE).edit().putString("accent_theme", accent).apply()
                        },
                        customThemeJson = customThemeJson,
                        onCustomThemeJsonChange = { json ->
                            customThemeJson = json
                            getSharedPreferences("account_ui", MODE_PRIVATE).edit().putString("custom_theme_json", json).apply()
                        }
                    )
                }
            }
        }
    }
}
