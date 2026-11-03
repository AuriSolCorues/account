package com.copy.account.sync.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWatchdogTest {

    @Test
    fun arm_appAlreadyLocked_notArmed() {
        assertEquals(0, armTimeoutSec(sessionAlive = false, autoLockSec = 60))
    }

    @Test
    fun arm_autoLockDisabled_notArmed() {
        assertEquals(0, armTimeoutSec(sessionAlive = true, autoLockSec = 0))
    }

    @Test
    fun arm_sessionAlive_passesTimeoutThrough() {
        assertEquals(30, armTimeoutSec(sessionAlive = true, autoLockSec = 30))
    }

    @Test
    fun expired_exactlyAtBoundary_true() {
        val anchor = 100_000L
        assertTrue(watchdogExpired(nowMs = anchor + 30_000L, anchorMs = anchor, timeoutSec = 30))
    }

    @Test
    fun expired_oneMsBeforeBoundary_false() {
        val anchor = 100_000L
        assertFalse(watchdogExpired(nowMs = anchor + 29_999L, anchorMs = anchor, timeoutSec = 30))
    }

    @Test
    fun expired_noAnchor_false() {
        assertFalse(watchdogExpired(nowMs = 1_000_000L, anchorMs = 0L, timeoutSec = 30))
    }

    @Test
    fun expired_notArmed_false() {
        val anchor = 100_000L
        assertFalse(watchdogExpired(nowMs = anchor + 999_999L, anchorMs = anchor, timeoutSec = 0))
    }
}
