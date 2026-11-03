package com.copy.account.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.copy.account.sync.data.BridgeClient
import com.copy.account.sync.runtime.IdleWatchdog
import com.copy.account.sync.runtime.PlatformMirror
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.themePaletteFromJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncHomeActivity : ComponentActivity() {

    private lateinit var mirror: PlatformMirror

    private lateinit var watchdog: IdleWatchdog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        mirror = PlatformMirror { BridgeClient(applicationContext).platformState() }
        mirror.refresh()
        mirror.register(this)
        watchdog = IdleWatchdog(this, lifecycleScope) {
            mirror.markLockedLocally()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { BridgeClient(this@SyncHomeActivity).releaseSession() }
            }
        }
        watchdog.start()
        window.decorView.postDelayed({ if (mirror.locked) mirror.refresh() }, 500L)
        applySystemBarAppearance()
        setContent {
            AccountTheme(
                darkTheme = isDarkMode(),
                accentTheme = mirror.state.accentTheme,
                customThemeJson = mirror.state.customThemeJson
            ) {
                SyncApp(mirror, onExit = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mirror.refresh()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        watchdog.onUserInteraction()
    }

    override fun onStop() {
        super.onStop()
        if (shouldRelockOnStop(isFinishing)) {
            BridgeClient(this).releaseSession()
            finish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        BridgeClient(this).scheduleResumeExtension()
    }

    override fun onDestroy() {
        super.onDestroy()
        mirror.unregister(this)
    }

    private fun applySystemBarAppearance() {
        val useDarkSystemIcons = themePaletteFromJson(mirror.state.customThemeJson)?.background
            ?.luminance()?.let { it > 0.5f } ?: !isDarkMode()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkSystemIcons
            isAppearanceLightNavigationBars = useDarkSystemIcons
        }
    }

    private fun isDarkMode(): Boolean = when (mirror.state.themeMode) {
        "light" -> false
        "system" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> true
    }
}

internal fun shouldRelockOnStop(isFinishing: Boolean): Boolean = !isFinishing
