package com.copy.account.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLockPolicyTest {

    @Test
    fun stop_finishing_backToHost_noRelock() {
        assertFalse(shouldRelockOnStop(isFinishing = true))
    }

    @Test
    fun stop_notFinishing_background_relock() {
        assertTrue(shouldRelockOnStop(isFinishing = false))
    }
}
