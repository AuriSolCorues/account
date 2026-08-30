package com.copy.account

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
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.themePaletteFromJson
import androidx.compose.ui.graphics.luminance

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到系统栏后方，让顶栏覆盖刘海区域，避免全屏设备出现白色边条。
        enableEdgeToEdge()
        setContent {
            var themeMode by remember {
                mutableStateOf("dark")
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
            SideEffect {
                if (allowScreenshots) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                }
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
