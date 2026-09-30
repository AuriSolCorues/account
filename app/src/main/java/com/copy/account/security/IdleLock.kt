/**
 * 职责：前台空闲自动锁定的计时与判定——「距上次交互超过阈值就锁」这条规则本身，
 *       排程（挂到主线程 Looper 的延迟回调）与时钟都由外部注入。
 * 架构位置：调用方（Activity 生命周期 / Compose 的交互回调）持有实例，start() 于进入前台时调用、
 *       touch() 挂在统一的手势分发处、stop() 于锁定或退到后台时调用；onLock 由调用方接到既有锁定流程。
 * Python 类比：now ≈ 注入的 time.monotonic()、IdleScheduler ≈ 可替换的定时器（如 APScheduler 里的
 *           call_later），生产环境才换成真实的 Handler 适配器；正因为定时器是替身，本类不碰任何
 *           android.* API，能直接在纯 JVM 单测里用假时钟跑完所有时间分支。
 */
package com.copy.account.security

import android.os.Handler
import android.os.Looper

/** 延迟排程的最小抽象：只要求「延迟 delayMs 毫秒后跑一次 task」和「全部撤回」。 */
internal interface IdleScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
    fun cancel()
}

/**
 * 前台空闲自动锁定的状态机。
 *
 * 关键约束：now 在生产环境必须传 `SystemClock.uptimeMillis()`，**不能**用 elapsedRealtime()
 * 或 currentTimeMillis()。Handler 的 postDelayed 以 uptime 为基准，而 elapsedRealtime 在深睡时
 * 仍继续前进、currentTimeMillis 会被改系统时间或 NTP 校准——两者都会让「已过时长」与回调实际
 * 触发的时刻对不上，轻则早锁晚锁，重则深睡一觉醒来立刻被锁。
 *
 * 之所以每步都重算「已过时长」而不是只信排程回调，是因为回调可能被主线程繁忙推迟很久；
 * 触发时用真实时钟二次校验，迟到了也照样锁。
 */
internal class IdleLock(
    private val onLock: () -> Unit,
    private val scheduler: IdleScheduler,
    private val now: () -> Long,
) {
    private var timeoutMs = 0L
    private var lastInteractionAt = 0L
    private var started = false

    /**
     * 开始计时：把「上次交互」锚定在当前时刻并排一个 timeoutMs 后的回调。
     * timeoutMs <= 0 视为关闭（自动锁定未启用），此时不排程、也不去撤旧回调——撤回调交给 stop()。
     * 重复调用即换阈值重排，mainThreadIdleScheduler 会先移除上一次的回调。
     */
    fun start(timeoutMs: Long) {
        if (timeoutMs <= 0) {
            started = false
            return
        }
        this.timeoutMs = timeoutMs
        lastInteractionAt = now()
        started = true
        scheduler.schedule(timeoutMs) { onTimeout() }
    }

    /**
     * 有交互发生就把锚点推到现在，并从新锚点起排一个完整超时；未启动时是空操作，碰都不碰 scheduler。
     * 必须排 timeoutMs 而不是「到旧截止点的剩余时间」：语义就是「每次交互后重新等满一段空闲」，
     * 排短了回调会提前到、被 onTimeout 的二次校验拒掉，而 Handler 回调只触发一次、被拒后无人重排，
     * 计时器就地死亡——本周期内再也不会锁（曾致导航去过设置/分组页后前台永不锁定）。
     */
    fun touch() {
        if (!started) return
        lastInteractionAt = now()
        scheduler.schedule(timeoutMs) { onTimeout() }
    }

    /** 停止计时并撤回回调；可重复调用（锁定、退后台、页面销毁都会走到）。 */
    fun stop() {
        started = false
        scheduler.cancel()
    }

    private fun onTimeout() {
        // 停用后的残留回调直接丢弃：stop() 之后连 fire 也不该锁。
        if (!started) return
        // 防竞态二次校验：上次交互后 1 秒就触发的回调（重排竞态、时钟跳变）不该锁。
        if (now() - lastInteractionAt < timeoutMs) return
        // 先置未启动再回调，保证一个超时周期内最多锁一次，重复触发不再重复锁。
        started = false
        onLock()
    }
}

/**
 * 生产环境的排程器：挂到主线程 Looper 的延迟回调（≈ loop.call_later(delay, fn)）。
 * 本文件里唯一接触 Android 的部分，其余全是纯 Kotlin——单测因此不必 Robolectric。
 */
internal fun mainThreadIdleScheduler(): IdleScheduler {
    val handler = Handler(Looper.getMainLooper())
    // 记住上一次的 Runnable 才能 removeCallbacks：Kotlin 的 () -> Unit 不是 Runnable，取不到它。
    var pending: Runnable? = null
    return object : IdleScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) {
            pending?.let(handler::removeCallbacks)
            val runnable = Runnable { task() }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }

        override fun cancel() {
            pending?.let(handler::removeCallbacks)
            pending = null
        }
    }
}
