/**
 * 职责：前台空闲自动锁定（security/IdleLock.kt）的单测——排程值、触发判定、交互重置、竞态二次校验。
 * 架构位置：本文件只用假时钟与记录型排程器，不依赖 Android/Robolectric，因此毫秒级的超时分支零等待跑完。
 */
package com.copy.account.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLockTest {

    private val clock = FakeClock()
    private val scheduler = RecordingScheduler()
    private var lockCount = 0
    private val lock = IdleLock(onLock = { lockCount++ }, scheduler = scheduler, now = { clock.now })

    @Test
    fun start_按给定的超时时长排程() {
        lock.start(60_000L)

        assertEquals(60_000L, scheduler.lastDelay)
    }

    /** 差 1 毫秒也不能锁：超时是「达到或超过」而非「接近」。 */
    @Test
    fun 未到超时触发回调不锁() {
        lock.start(60_000L)
        clock.advance(59_999L)

        scheduler.fire()

        assertEquals(0, lockCount)
    }

    @Test
    fun 到达超时触发一次锁() {
        lock.start(60_000L)
        clock.advance(60_000L)

        scheduler.fire()

        assertEquals(1, lockCount)
    }

    /** 同一周期内回调被重复触发（如重排竞态）也只锁一次。 */
    @Test
    fun 同一周期内重复触发回调只锁一次() {
        lock.start(60_000L)
        clock.advance(60_000L)

        scheduler.fire()
        scheduler.fire()
        scheduler.fire()

        assertEquals(1, lockCount)
    }

    /** 交互把截止时间推后：先前那次 fire 若因竞态早到，不应影响重排后的新周期。 */
    @Test
    fun 交互重置截止时间() {
        lock.start(60_000L)
        clock.advance(30_000L)
        lock.touch()
        clock.advance(30_000L)

        scheduler.fire()
        assertEquals("上次交互才过 30 秒，不该锁", 0, lockCount)

        clock.advance(30_000L)
        scheduler.fire()
        assertEquals(1, lockCount)
    }

    @Test
    fun touch_按剩余时间重排() {
        lock.start(60_000L)
        clock.advance(30_000L)

        lock.touch()

        assertEquals(30_000L, scheduler.lastDelay)
    }

    /** 停用后 touch 必须是彻底的空操作：既不锁，也不给排程器添新任务。 */
    @Test
    fun stop_之后touch无效() {
        lock.start(60_000L)
        lock.stop()
        val schedulesBeforeTouch = scheduler.scheduleCount

        lock.touch()
        scheduler.fire()

        assertEquals(0, lockCount)
        assertEquals("stop 后的 touch 不该排程", schedulesBeforeTouch, scheduler.scheduleCount)
        assertEquals(1, scheduler.cancelCount)
    }

    /** 设置里改了超时时长，重复 start 应以新阈值重排。 */
    @Test
    fun 重新start_换阈值会重排() {
        lock.start(60_000L)

        lock.start(120_000L)

        assertEquals(120_000L, scheduler.lastDelay)
    }

    /** 回调因主线程繁忙迟到：已过时长 >= 阈值，仍应锁定。 */
    @Test
    fun 迟到触发仍然锁定() {
        lock.start(60_000L)
        clock.advance(600_000L)

        scheduler.fire()

        assertEquals(1, lockCount)
    }

    /** 关闭自动锁定：既不排程，残留回调也锁不了。 */
    @Test
    fun start_零超时表示关闭不排程也不锁() {
        lock.start(0L)

        assertEquals(0, scheduler.scheduleCount)
        assertNull(scheduler.lastDelay)

        scheduler.fire()
        assertEquals(0, lockCount)
    }

    /** 已过时长超过阈值时不能排出负延迟。 */
    @Test
    fun 已超阈值时touch_排程值夹到零() {
        lock.start(60_000L)
        clock.advance(70_000L)

        lock.touch()

        assertEquals(0L, scheduler.lastDelay)
    }

    /** 关闭态下 touch 同样不排程——未启动时不该有任何排程副作用。 */
    @Test
    fun 未启动时touch_不触发任何排程() {
        lock.touch()

        assertTrue("未启动时 touch 不该排程", scheduler.delays.isEmpty())
    }
}

/** 假时钟：时间只在 advance 里走，测试不需要真的等。 */
private class FakeClock(var now: Long = 0L) {
    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * 记录型排程器：只记住收到的延迟与最后一次任务，由 fire() 手动触发。
 * cancel() 故意不清掉 task——真实场景里回调可能已出队撤不回来，这正是 IdleLock 那道
 * 「未启动直接返回」防线要挡的情况，清掉就测不到了。
 */
private class RecordingScheduler : IdleScheduler {
    val delays = mutableListOf<Long>()
    var lastDelay: Long? = null
        private set
    var cancelCount = 0
        private set
    private var pending: (() -> Unit)? = null

    val scheduleCount: Int get() = delays.size

    override fun schedule(delayMs: Long, task: () -> Unit) {
        delays += delayMs
        lastDelay = delayMs
        pending = task
    }

    override fun cancel() {
        cancelCount++
    }

    fun fire() {
        pending?.invoke()
    }
}
