/**
 * 职责：全应用唯一的 Activity——窗口宿主与 Compose 挂载点；只持有主题/截图开关这几个
 *       「窗口级」状态，其余一切（路由、数据、业务）都在 AccountApp。
 * 架构位置：系统启动 → onCreate → setContent 挂 AccountTheme + AccountApp。主题状态在这里
 *           remember、经参数下发 AccountApp、经回调收回——提升到最外层是因为
 *           FLAG_SECURE/状态栏颜色必须操作 window 对象，只有 Activity 够得着。
 * Python 类比：Activity ≈ 程序入口 if __name__ == "__main__" 加 tkinter 窗口宿主二合一；
 *           onCreate ≈ __init__ + 主循环前初始化。生命周期回调（ON_RESUME/ON_STOP 等）
 *           是系统调我们，不是我们调系统。
 */
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.themePaletteFromJson
import androidx.compose.ui.graphics.luminance

// 继承 FragmentActivity 而非普通 ComponentActivity：BiometricPrompt 需要 Fragment 宿主。
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到系统栏后方，让顶栏覆盖刘海区域，避免全屏设备出现白色边条。
        enableEdgeToEdge()
        // setContent：把 @Composable 函数树挂进本窗口——命令式 Activity 世界与声明式 Compose
        // 世界的交界；此后 UI 全部声明式描述，不再有 XML 布局与 findViewById。
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
            // 截图保护：allowScreenshots 变化时立即应用
            DisposableEffect(allowScreenshots) {
                if (allowScreenshots) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }
            // SideEffect：每次重组（重渲染）完成后同步执行一次——把状态推给 Compose 之外的
            // 命令式世界（这里的 window 属性）。DisposableEffect 则是「进入/离开」各执行一次
            // （带 onDispose 清理），两者触发时机不同。
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
