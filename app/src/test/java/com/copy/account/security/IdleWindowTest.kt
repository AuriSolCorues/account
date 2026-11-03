package com.copy.account.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWindowTest {

    private val anchor = 100_000L

    @Test
    fun `now恰好等于锚点加阈值_到期`() {
        assertTrue(idleExpired(nowMs = anchor + 30_000L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `now差一毫秒不到阈值_不到期`() {
        assertFalse(idleExpired(nowMs = anchor + 29_999L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `now超过阈值_到期`() {
        assertTrue(idleExpired(nowMs = anchor + 30_001L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `锚点为0且超时为0_永不到期`() {
        assertFalse(idleExpired(nowMs = 1_000_000L, anchorMs = 0L, timeoutMs = 0L))
    }

    @Test
    fun `阈值为0_锚点0时差为正_不到期`() {
        assertFalse(idleExpired(nowMs = 30_000L, anchorMs = 0L, timeoutMs = 0L))
    }

    @Test
    fun `锚点0_时差达到阈值_到期`() {
        assertTrue(idleExpired(nowMs = 30_000L, anchorMs = 0L, timeoutMs = 30_000L))
    }

    @Test
    fun `now早于锚点_负时差_不到期`() {
        assertFalse(idleExpired(nowMs = anchor - 1L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `负阈值_永不到期`() {
        assertFalse(idleExpired(nowMs = anchor + 30_000L, anchorMs = anchor, timeoutMs = -1L))
    }
}
