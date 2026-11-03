package com.copy.account.runtime

import android.os.SystemClock

object ExtensionPlatformState {
    @Volatile var locked: Boolean = true

    @Volatile var sessionBorrowed: Boolean = false

    @Volatile var borrowedAtMs: Long = 0L

    const val BORROW_TIMEOUT_MS: Long = 5 * 60 * 1000L

    @Volatile var themeMode: String = "system"
    @Volatile var accentTheme: String = "green"
    @Volatile var customThemeJson: String = ""

    @Volatile var extensionPackage: String? = null

    @Volatile var notifyContext: android.content.Context? = null

    fun notifyStateChanged() {
        val ctx = notifyContext ?: return
        val pkg = extensionPackage ?: return
        runCatching {
            ctx.sendBroadcast(
                android.content.Intent("com.copy.account.action.PLATFORM_STATE_CHANGED").setPackage(pkg)
            )
        }
    }

    @Volatile var releaseHook: (() -> Unit)? = null

    @Volatile var idleAnchorMs: Long = 0L

    @Volatile var idleTimeoutSec: Int = 0

    @Volatile var idleTouchHook: (() -> Unit)? = null

    fun borrowSession(nowMs: Long) {
        sessionBorrowed = true
        borrowedAtMs = nowMs
    }

    fun shouldLockOnStop(nowMs: Long): Boolean {
        if (!sessionBorrowed) return true
        if (nowMs - borrowedAtMs > BORROW_TIMEOUT_MS) {
            sessionBorrowed = false
            return true
        }
        return false
    }

    fun releaseSession() {
        sessionBorrowed = false
    }

    @Volatile private var resumeScheduledAtMs: Long? = null

    fun scheduleResumeExtension(nowMs: Long) {
        resumeScheduledAtMs = nowMs
    }

    fun consumeResumeExtension(nowMs: Long): Boolean {
        val at = resumeScheduledAtMs ?: return false
        resumeScheduledAtMs = null
        return nowMs - at <= RESUME_WINDOW_MS
    }

    private const val RESUME_WINDOW_MS = 2 * 60_000L
}
