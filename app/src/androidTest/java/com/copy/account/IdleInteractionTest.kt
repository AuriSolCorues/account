package com.copy.account

import android.os.SystemClock
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.copy.account.security.IdleLock
import com.copy.account.security.mainThreadIdleScheduler
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IdleInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun realTouchInvokesInteractionSink() {
        val counter = AtomicInteger(0)
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.userInteractionSink = { counter.incrementAndGet() }
        }
        val before = counter.get()

        composeRule.onRoot().performTouchInput { click() }

        assertTrue("真实触摸应触发 onUserInteraction → sink", counter.get() > before)
    }

    @Test
    fun realHandlerFiresLock() {
        val locked = CountDownLatch(1)
        val idleLock = IdleLock(onLock = { locked.countDown() }, scheduler = mainThreadIdleScheduler(), now = { SystemClock.uptimeMillis() })

        idleLock.start(300)

        assertTrue("300ms 后主线程 Handler 应触发 onLock", locked.await(3, TimeUnit.SECONDS))
        idleLock.stop()
    }
}
