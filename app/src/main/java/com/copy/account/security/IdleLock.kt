package com.copy.account.security

import android.os.Handler
import android.os.Looper
import com.copy.account.platform.idleExpired

internal interface IdleScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
    fun cancel()
}

internal class IdleLock(
    private val onLock: () -> Unit,
    private val scheduler: IdleScheduler,
    private val now: () -> Long,
    private val anchorExport: (() -> Unit)? = null,
) {
    private var timeoutMs = 0L
    private var lastInteractionAt = 0L
    private var started = false

    fun start(timeoutMs: Long) {
        if (timeoutMs <= 0) {
            started = false
            return
        }
        this.timeoutMs = timeoutMs
        lastInteractionAt = now()
        started = true
        anchorExport?.invoke()
        scheduler.schedule(timeoutMs) { onTimeout() }
    }

    fun touch() {
        if (!started) return
        lastInteractionAt = now()
        anchorExport?.invoke()
        scheduler.schedule(timeoutMs) { onTimeout() }
    }

    fun stop() {
        started = false
        scheduler.cancel()
    }

    private fun onTimeout() {
        if (!started) return
        if (!idleExpired(now(), lastInteractionAt, timeoutMs)) return
        started = false
        onLock()
    }
}

internal fun mainThreadIdleScheduler(): IdleScheduler {
    val handler = Handler(Looper.getMainLooper())
    var pending: Runnable? = null
    return object : IdleScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) {
            pending?.let(handler::removeCallbacks)
            val runnable = Runnable { task() }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }

        override fun cancel() {
            pending?.let(handler::removeCallbacks)
            pending = null
        }
    }
}
