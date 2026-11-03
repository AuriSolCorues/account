package com.copy.account.sync.runtime

import android.os.SystemClock
import com.copy.account.platform.idleExpired
import com.copy.account.sync.data.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class IdleWatchdog(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
) {
    @Volatile private var anchorMs = 0L

    @Volatile private var timeoutSec = 0

    private var lastBeatAt = 0L

    fun onUserInteraction() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeatAt < BEAT_INTERVAL_MS) return
        lastBeatAt = now
        scope.launch(Dispatchers.IO) {
            val reply = BridgeClient(context).touchSession() ?: run {
                timeoutSec = 0
                return@launch
            }
            anchorMs = reply.lastTouchMs
            timeoutSec = armTimeoutSec(reply.sessionAlive, reply.autoLockSec)
        }
    }

    fun start() {
        scope.launch {
            while (isActive) {
                delay(1000L)
                val t = timeoutSec
                if (t <= 0 || anchorMs <= 0L) continue
                if (watchdogExpired(SystemClock.uptimeMillis(), anchorMs, t)) {
                    timeoutSec = 0
                    onExpired()
                }
            }
        }
    }

    companion object {
        private const val BEAT_INTERVAL_MS = 1000L
    }
}

internal fun armTimeoutSec(sessionAlive: Boolean, autoLockSec: Int): Int =
    if (sessionAlive) autoLockSec else 0

internal fun watchdogExpired(nowMs: Long, anchorMs: Long, timeoutSec: Int): Boolean =
    anchorMs > 0 && idleExpired(nowMs, anchorMs, timeoutSec * 1000L)
