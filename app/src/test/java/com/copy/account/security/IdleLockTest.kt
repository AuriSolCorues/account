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

    @Test
    fun 同一周期内重复触发回调只锁一次() {
        lock.start(60_000L)
        clock.advance(60_000L)

        scheduler.fire()
        scheduler.fire()
        scheduler.fire()

        assertEquals(1, lockCount)
    }

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
    fun touch_按完整超时重排() {
        lock.start(60_000L)
        clock.advance(30_000L)

        lock.touch()

        assertEquals(60_000L, scheduler.lastDelay)
    }

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

    @Test
    fun 重新start_换阈值会重排() {
        lock.start(60_000L)

        lock.start(120_000L)

        assertEquals(120_000L, scheduler.lastDelay)
    }

    @Test
    fun 迟到触发仍然锁定() {
        lock.start(60_000L)
        clock.advance(600_000L)

        scheduler.fire()

        assertEquals(1, lockCount)
    }

    @Test
    fun start_零超时表示关闭不排程也不锁() {
        lock.start(0L)

        assertEquals(0, scheduler.scheduleCount)
        assertNull(scheduler.lastDelay)

        scheduler.fire()
        assertEquals(0, lockCount)
    }

    @Test
    fun touch后_新回调到达新截止点即锁() {
        lock.start(60_000L)
        clock.advance(30_000L)
        lock.touch()
        scheduler.fire()
        assertEquals(0, lockCount)

        clock.advance(60_000L)
        scheduler.fire()
        assertEquals(1, lockCount)
    }

    @Test
    fun 未启动时touch_不触发任何排程() {
        lock.touch()

        assertTrue("未启动时 touch 不该排程", scheduler.delays.isEmpty())
    }
}

private class FakeClock(var now: Long = 0L) {
    fun advance(ms: Long) {
        now += ms
    }
}

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
