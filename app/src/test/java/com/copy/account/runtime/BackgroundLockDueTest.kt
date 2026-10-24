/**
 * 职责：后台延迟锁到期判定 backgroundLockDue（runtime/UnlockSession.kt 顶层纯函数）的契约单测——
 *       「立即档」语义、`>=` 同秒边界、与调用方的档位裁剪分工。
 * 架构位置：:app 测试源集（纯 JVM）；判定契约与 idleExpired（IdleWindowTest）同源，
 *           交叉断言钉在彼处。
 */
package com.copy.account.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLockDueTest {

    // ===== 档位语义：0 = 立即档 =====

    @Test
    fun `delay为0_立即档_任意时距都到期`() {
        // delaySec=0 不经 Handler：onAppStop 现场就锁（本函数在延迟分支也不会被问到），
        // 防御性兜底：任何 elapsed（含负样本）都判到期。
        assertTrue(backgroundLockDue(elapsedMs = 0L, delaySec = 0))
        assertTrue(backgroundLockDue(elapsedMs = -1L, delaySec = 0))
        assertTrue(backgroundLockDue(elapsedMs = 123_456L, delaySec = 0))
    }

    // ===== `>=` 同秒边界 =====

    @Test
    fun `差一毫秒待满_不到期`() {
        // elapsed=4999, delay=5：倒计时未走满，绝不提前锁。
        assertFalse(backgroundLockDue(elapsedMs = 4_999L, delaySec = 5))
    }

    @Test
    fun `恰好待满_到期`() {
        // elapsed=5000, delay=5：契约点——真在后台待满 5 秒的那一刻必须锁（`>=`），
        // 与前台空闲锁的「同秒锁定」契约同一条。
        assertTrue(backgroundLockDue(elapsedMs = 5_000L, delaySec = 5))
    }

    @Test
    fun `超过待满时长_到期`() {
        // elapsed=5001：回调迟到（主线程繁忙/Handler 抖动）照样锁。
        assertTrue(backgroundLockDue(elapsedMs = 5_001L, delaySec = 5))
    }

    @Test
    fun `大幅迟到_到期`() {
        assertTrue(backgroundLockDue(elapsedMs = 600_000L, delaySec = 5))
    }

    // ===== 与调用方的分工：本函数不裁剪档位 =====

    @Test
    fun `超上限档位不裁剪_透传给函数`() {
        // 分工注记：coerceIn(0, 300) 由调用方（SettingsContainer/AccountApp 的
        // backgroundLockSeconds 入口）完成；本纯函数只透传。delaySec=301 照算——
        // 301*1000=301000ms 已过即到期，语义不因越界档位改变。
        assertFalse(backgroundLockDue(elapsedMs = 300_999L, delaySec = 301))
        assertTrue(backgroundLockDue(elapsedMs = 301_000L, delaySec = 301))
    }
}
