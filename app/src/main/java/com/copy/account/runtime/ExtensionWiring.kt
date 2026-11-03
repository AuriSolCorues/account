package com.copy.account.runtime

import android.content.Context
import android.content.Intent

object ExtensionWiring {
    fun pushTheme(themeMode: String, accentTheme: String, customThemeJson: String) {
        ExtensionPlatformState.themeMode = themeMode
        ExtensionPlatformState.accentTheme = accentTheme
        ExtensionPlatformState.customThemeJson = customThemeJson
        ExtensionPlatformState.notifyStateChanged()
    }

    fun onLocked() {
        ExtensionPlatformState.locked = true
        ExtensionPlatformState.notifyStateChanged()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { ExtensionPlatformState.notifyStateChanged() }, 300L
        )
    }

    fun onUnlocked() {
        ExtensionPlatformState.locked = false
        ExtensionPlatformState.notifyStateChanged()
    }

    fun registerReleaseHook(hook: () -> Unit) {
        ExtensionPlatformState.releaseHook = hook
    }

    fun shouldLockOnStop(nowMs: Long): Boolean = ExtensionPlatformState.shouldLockOnStop(nowMs)

    fun exportIdleAnchor(nowMs: Long) {
        ExtensionPlatformState.idleAnchorMs = nowMs
    }

    fun registerIdleHooks(touchHook: () -> Unit) {
        ExtensionPlatformState.idleTouchHook = touchHook
    }

    fun unregisterIdleHooks() {
        ExtensionPlatformState.idleTouchHook = null
    }

    fun updateIdleTimeoutSec(sec: Int) {
        ExtensionPlatformState.idleTimeoutSec = sec
    }

    fun consumeResumeAndLaunch(context: Context): Boolean {
        val consumed = ExtensionPlatformState.consumeResumeExtension(android.os.SystemClock.uptimeMillis())
        if (consumed) launchExtensionSettings(context)
        return consumed
    }

    fun attachNotifyContext(context: Context) {
        ExtensionPlatformState.notifyContext = context
    }

    fun onSessionEnd() {
        ExtensionPlatformState.releaseSession()
    }

    fun resolveLaunchIntent(context: Context): Intent? = resolveExtensionSettingsIntent(context)

    fun setExtensionPackage(pkg: String?) {
        ExtensionPlatformState.extensionPackage = pkg
    }
}
