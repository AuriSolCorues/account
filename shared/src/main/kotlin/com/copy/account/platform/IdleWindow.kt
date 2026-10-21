/**
 * 空闲到期判定的单一事实源（shared/ 经 srcDirs 双挂进 :app 与可选扩展模块，同一份字节码两边跑）。
 * 消费方共三处：
 *   ① 本体 [com.copy.account.security.IdleLock.onTimeout] 的防竞态二次校验（前台空闲锁）；
 *   ② 本体侧后续延迟锁入口（同秒边界同款契约，公开线按需接入）；
 *   ③ 扩展侧看门狗（如 :extension 模块的 IdleWatchdog，每秒对表）。
 * 位置注记：文件物理上住 shared/platform/（扩展模块的 srcDirs 只按子目录裁剪挂载，
 *   security/ 未挂），包名取 com.copy.account.platform（与物理目录一致；Kotlin 编译器要求
 *   srcDir 根下 package 声明与物理路径对齐，包名错位会让两侧双双 Unresolved）。
 *   本体/扩展消费方各自 import。
 * 任何一处判定漂移都会破坏「同时锁定」：本体空闲锁锚点（uptimeMillis 钟）经 touchSession
 * 心跳给扩展，扩展每秒本地倒计时，到点与本体 lockApp 同一秒各自动作——三处手写判定迟早
 * 走样（有人改 < 有人改 <=、有人忘了哨兵值），差一毫秒，两端就差一整个回调周期，同秒锁定
 * 作废。所以收敛成这一个函数，测试钉死边界（IdleWindowTest）。
 *
 * 边界契约：`>=`。nowMs - anchorMs == timeoutMs 的那一刻【必须】判到期（整秒必须算），
 * 差 1ms（== timeoutMs - 1）【不算】——提前翻锁会让扩展先盖遮罩、本体迟一步，用户看到
 * 两次闪锁。这正是 IdleLockTest.到达超时触发一次锁 / IdleWatchdogTest.expired_exactlyAtBoundary_true
 * 钉住的同一个点。
 *
 * 时钟契约：nowMs / anchorMs 必须同源于 SystemClock.uptimeMillis()（禁 elapsedRealtime——
 * 深睡仍前进，会让「已过时长」与 Handler postDelayed 的 uptime 基准错位；禁
 * currentTimeMillis——改系统时间/NTP 校准会凭空跳变）。Handler 的 postDelayed 本就以
 * uptime 为基准，全链同钟才能保证二次校验与回调触发时刻对得上。
 *
 * 哨兵值分工：本函数【不】设 anchorMs>0 哨兵——0 是合法的 uptime 取值（开机瞬间的交互、
 * 测试假时钟原点），IdleLock 的 lastInteractionAt 就可以是 0；「无锚点/未上报」的 0 哨兵
 * 属于看门狗域（心跳从没上报过来），收口在 watchdogExpired 的入口判断里（由
 * IdleWatchdogTest.expired_noAnchor_false 钉死）。timeoutMs>0 哨兵在本函数收口：
 * 0/负值 = 未启用，永不到期。
 *
 * @param nowMs     当前时刻（uptimeMillis）
 * @param anchorMs  最近一次交互锚点（uptimeMillis）；0 是合法时刻，不是哨兵
 * @param timeoutMs 超时阈值（毫秒）；0 或负值 = 未启用，永不到期
 * @return true = 已到期应立即执行锁定动作
 */
package com.copy.account.platform

internal fun idleExpired(nowMs: Long, anchorMs: Long, timeoutMs: Long): Boolean =
    timeoutMs > 0 && nowMs - anchorMs >= timeoutMs

