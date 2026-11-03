package com.copy.account.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLockDueTest {

    @Test
    fun `delay为0_立即档_任意时距都到期`() {
        assertTrue(backgroundLockDue(elapsedMs = 0L, delaySec = 0))
        assertTrue(backgroundLockDue(elapsedMs = -1L, delaySec = 0))
        assertTrue(backgroundLockDue(elapsedMs = 123_456L, delaySec = 0))
    }

    @Test
    fun `差一毫秒待满_不到期`() {
        assertFalse(backgroundLockDue(elapsedMs = 4_999L, delaySec = 5))
    }

    @Test
    fun `恰好待满_到期`() {
        assertTrue(backgroundLockDue(elapsedMs = 5_000L, delaySec = 5))
    }

    @Test
    fun `超过待满时长_到期`() {
        assertTrue(backgroundLockDue(elapsedMs = 5_001L, delaySec = 5))
    }

    @Test
    fun `大幅迟到_到期`() {
        assertTrue(backgroundLockDue(elapsedMs = 600_000L, delaySec = 5))
    }

    @Test
    fun `超上限档位不裁剪_透传给函数`() {
        assertFalse(backgroundLockDue(elapsedMs = 300_999L, delaySec = 301))
        assertTrue(backgroundLockDue(elapsedMs = 301_000L, delaySec = 301))
    }
}
