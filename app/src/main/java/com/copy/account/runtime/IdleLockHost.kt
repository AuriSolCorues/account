package com.copy.account.runtime

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.copy.account.BuildConfig
import com.copy.account.MainActivity
import com.copy.account.security.IdleLock
import com.copy.account.security.mainThreadIdleScheduler

@Composable
internal fun IdleLockHost(
    activity: ComponentActivity?,
    isUnlocked: Boolean,
    autoLockSeconds: Int,
    onLock: () -> Unit,
) {
    val currentOnLock by rememberUpdatedState(onLock)
    val currentAutoLockSeconds by rememberUpdatedState(autoLockSeconds)
    val idleLock = remember {
        IdleLock(
            onLock = { currentOnLock() },
            scheduler = mainThreadIdleScheduler(),
            now = { SystemClock.uptimeMillis() },
            anchorExport = { ExtensionWiring.exportIdleAnchor(SystemClock.uptimeMillis()) },
        )
    }
    DisposableEffect(activity) {
        val host = activity as? MainActivity
        host?.userInteractionSink = { idleLock.touch() }
        ExtensionWiring.registerIdleHooks(touchHook = { idleLock.touch() })
        onDispose {
            host?.userInteractionSink = null
            ExtensionWiring.unregisterIdleHooks()
            idleLock.stop()
        }
    }
    LaunchedEffect(isUnlocked, currentAutoLockSeconds) {
        val testSeconds = BuildConfig.AUTO_LOCK_TEST_SECONDS
        val timeoutMs = when {
            !isUnlocked || currentAutoLockSeconds <= 0 -> 0L
            testSeconds > 0L -> testSeconds * 1000L
            else -> currentAutoLockSeconds * 1000L
        }
        if (timeoutMs <= 0L) {
            idleLock.stop()
            ExtensionWiring.updateIdleTimeoutSec(0)
        } else {
            idleLock.start(timeoutMs)
            ExtensionWiring.updateIdleTimeoutSec((timeoutMs / 1000L).toInt())
        }
    }
}
