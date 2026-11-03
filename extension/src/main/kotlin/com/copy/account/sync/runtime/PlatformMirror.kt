package com.copy.account.sync.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.copy.account.sync.data.PlatformState

const val PLATFORM_STATE_ACTION = "com.copy.account.action.PLATFORM_STATE_CHANGED"

internal class PlatformMirror(private val readState: () -> PlatformState) {

    var state by mutableStateOf(PlatformState(locked = true, themeMode = "system", accentTheme = "green", customThemeJson = ""))
        private set

    val locked: Boolean get() = state.locked || state.unreachable || lockedLocally

    private var lockedLocally by androidx.compose.runtime.mutableStateOf(false)

    fun markLockedLocally() {
        lockedLocally = true
    }

    private val stateReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == PLATFORM_STATE_ACTION) refresh()
            }
        }
    }

    fun refresh() {
        state = readState()
        if (!state.locked && !state.unreachable) lockedLocally = false
    }

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(stateReceiver, IntentFilter(PLATFORM_STATE_ACTION), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, IntentFilter(PLATFORM_STATE_ACTION))
        }
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(stateReceiver) }
    }
}
