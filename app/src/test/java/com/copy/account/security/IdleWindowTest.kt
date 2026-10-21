/**
 * 职责：共享空闲到期判定 idleExpired（shared/platform/IdleWindow.kt）的契约单测——
 *       `>=` 同秒边界、哨兵值（无锚点/未启用）。
 * 架构位置：:app 测试源集（纯 JVM；函数本体经 srcDirs 双挂进 :app 与可选扩展模块，同一份源码）。
 * 注：文件物理路径 shared/platform/（扩展模块的 srcDirs 裁剪挂载所致），包名随物理路径取
 *     com.copy.account.platform。
 */
package com.copy.account.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWindowTest {

    /** 固定锚点：与 IdleWatchdogTest 同款量级，避免可读性干扰。 */
    private val anchor = 100_000L

    // ===== `>=` 同秒边界契约 =====

    @Test
    fun `now恰好等于锚点加阈值_到期`() {
        // 契约点本体：now - anchor == timeoutMs 必须判 true（整秒必须算）——
        // 本体 lockApp 与扩展看门狗同秒各自动作就掐在这一点上，差一毫秒都不行。
        assertTrue(idleExpired(nowMs = anchor + 30_000L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `now差一毫秒不到阈值_不到期`() {
        // 差 1ms（== timeoutMs - 1）不算：提前翻锁会让扩展先盖遮罩、本体迟一步，两次闪锁。
        assertFalse(idleExpired(nowMs = anchor + 29_999L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `now超过阈值_到期`() {
        // 回调迟到（主线程繁忙）场景：已过时长 >= 阈值，照锁不误。
        assertTrue(idleExpired(nowMs = anchor + 30_001L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    // ===== 哨兵值与负值路径 =====

    @Test
    fun `锚点为0且超时为0_永不到期`() {
        // timeoutMs=0 = 未启用/未武装（自动锁定关闭、心跳失败）→ 永不到期。
        // 注意锚点 0 本身是合法 uptime 时刻（非哨兵）：假时钟原点、开机瞬间的交互都是 0，
        // 所以本测试钉的是「阈值哨兵」而非「锚点哨兵」——锚点哨兵属看门狗域（见 IdleWatchdogTest）。
        assertFalse(idleExpired(nowMs = 1_000_000L, anchorMs = 0L, timeoutMs = 0L))
    }

    @Test
    fun `阈值为0_锚点0时差为正_不到期`() {
        // timeoutMs=0：无论时差多少都未启用。锚点 0 + 时差 30s 也不例外。
        assertFalse(idleExpired(nowMs = 30_000L, anchorMs = 0L, timeoutMs = 0L))
    }

    @Test
    fun `锚点0_时差达到阈值_到期`() {
        // 反向契约点：锚点 0 是合法值（开机瞬间的交互/测试假时钟原点），时差到了照样到期。
        // 这也是 IdleLockTest 假时钟（FakeClock 从 0 起步）「到达超时触发一次锁」跑的真实路径。
        assertTrue(idleExpired(nowMs = 30_000L, anchorMs = 0L, timeoutMs = 30_000L))
    }

    @Test
    fun `now早于锚点_负时差_不到期`() {
        // 负值路径：时钟取到 anchor 之前的样本（重排竞态下的旧锚点）→ 判不延期。
        assertFalse(idleExpired(nowMs = anchor - 1L, anchorMs = anchor, timeoutMs = 30_000L))
    }

    @Test
    fun `负阈值_永不到期`() {
        // 防御：负阈值等价未启用（调用方均保证非负，纯函数自我防御）。
        assertFalse(idleExpired(nowMs = anchor + 30_000L, anchorMs = anchor, timeoutMs = -1L))
    }
}
