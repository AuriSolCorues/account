/**
 * 职责：前台空闲自动锁定的全部「接线」——真实 Handler 排程 + uptimeMillis 时钟 + 用户交互
 *       sink 挂载 + 超时启停。判定规则本体在 security/IdleLock（纯 Kotlin、可单测），
 *       这里只负责把它接进 Compose：创建实例、把 MainActivity.userInteractionSink 指到
 *       touch()、按解锁状态与设置时长 start/stop。
 * 架构位置：AccountApp（调用方）组合树中的一层薄壳；IdleLockHost 不持有任何业务状态，
 *           onLock / isUnlocked / autoLockMinutes 全部经参数流入，经 rememberUpdatedState
 *           防闭包陈旧后转交给只创建一次的 IdleLock 实例。
 * Python 类比：≈ 一个挂在主窗口上的装饰器/上下文管理器——把「每次交互重置计时器」绑到
 *           事件循环（Handler ≈ loop.call_later），把「到点回调」绑到既有的锁定函数；
 *           计时器逻辑在别的模块，这里只做 connect。
 */
package com.copy.account.runtime

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.copy.account.BuildConfig
import com.copy.account.MainActivity
import com.copy.account.security.IdleLock
import com.copy.account.security.mainThreadIdleScheduler

/**
 * 前台空闲自动锁定的接线（自 AccountApp 原地逻辑整体抽出）。
 *
 * 为什么要 rememberUpdatedState：下面的 onLock 与 autoLockMinutes 都进 remember 起来的
 * lambda／effect 闭包，而锁实例只创建一次——闭包会永远捕获调用方首帧那次组合的值。用户改了
 * 「自动锁定」时长后，读到的仍是旧值，且不报任何错，只表现为「改了没反应」。
 * 换成 State 引用后每次调用都现读现用，永远是最新值。
 * 注意不能写成 rememberUpdatedState { ... }：尾随 lambda 会被当成「要持有的值」本身
 * （即 T = () -> Int），而不是每次求值的计算块，于是 currentAutoLockMinutes 会变成函数类型。
 */
@Composable
internal fun IdleLockHost(
    activity: ComponentActivity?,
    isUnlocked: Boolean,
    autoLockMinutes: Int,
    onLock: () -> Unit,
) {
    val currentOnLock by rememberUpdatedState(onLock)
    val currentAutoLockMinutes by rememberUpdatedState(autoLockMinutes)
    // 生产接线：真实主线程 Handler 排程 + uptimeMillis 时钟（必须与 postDelayed 同基准，
    // 深睡不前进、不受改系统时间影响）。
    val idleLock = remember { IdleLock(onLock = { currentOnLock() }, scheduler = mainThreadIdleScheduler(), now = { SystemClock.uptimeMillis() }) }
    DisposableEffect(activity) {
        // Activity 上没有「本地 Context 就是 MainActivity」的类型保证（预览/测试会传别的），
        // 拿不到宿主就干脆不挂 sink，onUserInteraction 里的 ?.invoke() 会自然跳过。
        val host = activity as? MainActivity
        host?.userInteractionSink = { idleLock.touch() }
        onDispose {
            host?.userInteractionSink = null
            idleLock.stop()
        }
    }
    // autoLockMinutes == 0 是用户显式选的「关闭」，这个判断在最前面：测试钩子也不许绕过它。
    LaunchedEffect(isUnlocked, currentAutoLockMinutes) {
        val testSeconds = BuildConfig.AUTO_LOCK_TEST_SECONDS
        val timeoutMs = when {
            !isUnlocked || currentAutoLockMinutes <= 0 -> 0L
            // debug 包把超时压到几秒，真机手测不用干等一分钟；release 恒为 0，不覆盖真实设置。
            testSeconds > 0L -> testSeconds * 1000L
            else -> currentAutoLockMinutes * 60_000L
        }
        if (timeoutMs > 0L) idleLock.start(timeoutMs) else idleLock.stop()
    }
}
