/**
 * 职责：可选扩展的平台状态源（本体进程内存单例）——锁态 + 会话让渡 + 外观（主题三值），
 *       桥的 state 管道从这里出数。**进程内存态，非磁盘**：本体进程死亡 = 单例归零 =
 *       locked 缺省 true（fail-closed 天生成立）；锁定即赋值，零 IO，ON_STOP 后帧停产
 *       也不再有落盘时机问题。
 *       会话让渡（borrow）：本体主动拉起扩展页时，本体 Activity 必然 ON_STOP——但那是
 *       「让渡给扩展」不是「用户离开应用」。borrow=true 期间 onAppStop 不锁；扩展侧
 *       ON_STOP（扩展页自己退后台）经 releaseSession 回锁。看门狗兜底：扩展崩溃不归还
 *       时，借出超时后下次 onAppStop 照锁（密码学上无新洞：DEK 只在内存，锁只是清它）。
 * 架构位置：runtime/ 层；UnlockSession（锁翻转/让渡判定）、AccountApp（主题回推/
 *           让渡触发）、ExtensionBridgeProvider（state 管道）读写。
 * Python 类比：≈ 一个模块级全局配置对象——写方三两个，读方只有桥的序列化点。
 */
package com.copy.account.runtime

import android.os.SystemClock

object ExtensionPlatformState {
    /** 本体锁定态（true = 扩展必须拒活）。缺省 true：进程刚起/未解锁都算锁定。 */
    @Volatile var locked: Boolean = true

    /**
     * 会话让渡标志：本体设置页拉起扩展页 = true（此时本体 ON_STOP 不触发锁定）；
     * 扩展页自己退到后台/被销毁 = false（其 ON_STOP 立即回锁）。缺省 false = 保守。
     * 普通 @Volatile（曾改 mutableStateOf 供 IdleLockHost 读「借出即暂停空闲锁」——
     * 该设计已废除：扩展页停多久本体都不锁 = 自动锁定失效，改为「扩展触摸心跳
     * 计入同一把空闲锁 + 两端共享锚点同秒到点」，见 idleTouchHook）。
     */
    @Volatile var sessionBorrowed: Boolean = false

    /** 让渡时刻（uptimeMs），看门狗判定用：借出超过 [BORROW_TIMEOUT_MS] 视为扩展已死。 */
    @Volatile var borrowedAtMs: Long = 0L

    /** 看门狗阈值：正常浏览扩展页几分钟足够；超时后 onAppStop 恢复无条件锁定。 */
    const val BORROW_TIMEOUT_MS: Long = 5 * 60 * 1000L

    /** 外观三值（与 MainActivity AccountTheme 参数同源同语义）。 */
    @Volatile var themeMode: String = "system"
    @Volatile var accentTheme: String = "green"
    @Volatile var customThemeJson: String = ""

    /**
     * 状态变化通知（本体 → 扩展的推模型）：锁翻转/主题变更时广播给扩展。
     * **定向发送**（setPackage 扩展包名）：Android 8+ 隐式广播受严格限制，ColorOS
     * 后台管控更是直接丢弃（真机 dumpsys 实证：隐式发的一条都没投出去）——定向豁免。
     * 包名不硬编码：由设置页「扩展」入口的在装发现（resolveExtensionSettingsIntent）
     * 顺手缓存；无扩展在装 = null = 不发（广播本就无人收）。
     * 纯尽力而为：扩展进程死时收不到，扩展侧 onResume 重拉兜底（推拉结合）。
     */
    @Volatile var extensionPackage: String? = null

    @Volatile var notifyContext: android.content.Context? = null

    fun notifyStateChanged() {
        val ctx = notifyContext ?: return
        val pkg = extensionPackage ?: return
        runCatching {
            ctx.sendBroadcast(
                android.content.Intent("com.copy.account.action.PLATFORM_STATE_CHANGED").setPackage(pkg)
            )
        }
    }

    /**
     * 归还钩子：由 UnlockSession 在会话建立时注册（= 真 lockApp：清 DEK/撤填充桥）。
     * null（本体没解锁过/会话已销毁）时 provider 收到回锁广播只翻单例——保守正确。
     */
    @Volatile var releaseHook: (() -> Unit)? = null

    // ===== 空闲锁共享锚点（「同时锁定」机制的核心状态）=====
    // 本体 IdleLockHost 每次触摸/启动时刷新 anchorMs 并导出超时秒数；扩展 touchSession
    // 心跳拿同一锚点+同一超时+同一时钟（uptimeMillis）本地倒计时——两端到点同秒，
    // 本体 lockApp 与扩展遮罩各自触发，无需本体主动推送（通道本就不存在）。

    /** 空闲锚点（uptimeMillis）：最近一次「任何侧」用户交互的本体侧记录。 */
    @Volatile var idleAnchorMs: Long = 0L

    /** 自动锁定超时秒数（0 = 关闭/未解锁，扩展侧见 0 即不武装本地看门狗）。 */
    @Volatile var idleTimeoutSec: Int = 0

    /**
     * 空闲锁 touch 钩子：IdleLockHost 挂载（= idleLock.touch()），provider 收到扩展
     * touchSession 心跳后 post 回主线程调用——扩展的活动由此计入本体同一把空闲锁。
     * null（预览/未组合）时心跳只回读锚点，不 touch（保守无害）。
     */
    @Volatile var idleTouchHook: (() -> Unit)? = null

    /** 借出（本体拉起扩展页时调用）。超时防呆：已借出再借只刷新时刻，不叠加。 */
    fun borrowSession(nowMs: Long) {
        sessionBorrowed = true
        borrowedAtMs = nowMs
    }

    /**
     * 归还判定（本体 onAppStop 时调用）：非借出态 → 照锁；
     * 借出态且未超时 → 不锁（扩展正在用会话）；借出但超时 → 恢复锁定并清标志。
     */
    fun shouldLockOnStop(nowMs: Long): Boolean {
        if (!sessionBorrowed) return true
        if (nowMs - borrowedAtMs > BORROW_TIMEOUT_MS) {
            sessionBorrowed = false
            return true
        }
        return false
    }

    /** 显式归还（扩展页销毁/回前台回到本体时调用），回锁由调用方执行。 */
    fun releaseSession() {
        sessionBorrowed = false
    }

    // ===== 解锁后回跳扩展页（一次性标记）：扩展页锁定态跳本体解锁前，经 provider 置位；
    //       onUnlocked 消费——借出会话 + 拉起扩展页，用户落回原页面。时间窗防呆抄 borrow：====
    /** 标记时刻（uptimeMillis）；null = 无待回跳。@Volatile：provider（Binder 线程）写、UI 读。 */
    @Volatile private var resumeScheduledAtMs: Long? = null

    /** 扩展页跳本体解锁前置位（provider call() 线程）。 */
    fun scheduleResumeExtension(nowMs: Long) {
        resumeScheduledAtMs = nowMs
    }

    /**
     * 解锁完成时消费：2 分钟窗口内有效（过期作废——解锁拖太久多半不是同一意图），
     * 取走即清（一次性）。有效返回 true，调用方应 borrowSession + 拉起扩展页。
     */
    fun consumeResumeExtension(nowMs: Long): Boolean {
        val at = resumeScheduledAtMs ?: return false
        resumeScheduledAtMs = null
        return nowMs - at <= RESUME_WINDOW_MS
    }

    /** 回跳标记有效期：指纹/密码解锁的正常耗时内有效，过期即弃。 */
    private const val RESUME_WINDOW_MS = 2 * 60_000L
}
