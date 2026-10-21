/**
 * 职责：扩展模块的接线门面（Wiring）——主体业务文件（AccountApp / UnlockSession /
 *       IdleLockHost / SettingsScreen）对 ExtensionPlatformState 的全部直接读写收拢于此，
 *       宿主文件零 ExtensionPlatformState 引用，只以稳定形态的具名函数调用接触扩展。
 * 架构意图：目标架构 = 公开仓库含「扩展插座底座」（BridgeContract / ExtensionPlatform /
 *       Provider / Host / Manifest 权限全公开），私有增量只剩 :extension 扩展目录与构建脚本。
 *       公开线与私有线要 git merge 恒零冲突，唯一办法是两侧「永远不碰同一行」：宿主文件
 *       的扩展接触行收口成对本 object 的调用后形态恒定——公开版本文件给直通/空实现、
 *       私有版给完整实现，宿主文件两侧一字不差，本文件各自整文件替换。
 *       行为零变化：下列函数全部是原调用点的逐行平移（含锁定双广播的 300ms Handler
 *       延迟兜底、主题无条件推送等时序细节，一字未改）。
 * 架构位置：runtime/ 层，与 ExtensionPlatformState 同包（顶层 object，既定风格）。
 * Python 类比：≈ facade——业务模块 import wiring 调函数，从不摸内部状态字典。
 */
package com.copy.account.runtime

import android.content.Context
import android.content.Intent

object ExtensionWiring {
    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：设置生效时把外观三值喂进平台状态单例并广播。
     * 原逻辑为无条件 notify（扩展页不重开也跟随主题），原样平移。
     * 消费方：AccountApp（rememberSettingsContainer 的 onThemeApplied 回调）。
     */
    fun pushTheme(themeMode: String, accentTheme: String, customThemeJson: String) {
        ExtensionPlatformState.themeMode = themeMode
        ExtensionPlatformState.accentTheme = accentTheme
        ExtensionPlatformState.customThemeJson = customThemeJson
        // 主题变化也推送（扩展页不重开即跟随）
        ExtensionPlatformState.notifyStateChanged()
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：锁态翻转 true + 双广播——立即一次 +
     * 300ms Handler 延迟兜底一次（ColorOS 冻结/投递抖动场景），时序逐行平移一字不差
     * （每次调用新建 Handler，与原实现一致）。
     * 消费方：UnlockSession.lockApp。
     */
    fun onLocked() {
        ExtensionPlatformState.locked = true
        ExtensionPlatformState.notifyStateChanged()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { ExtensionPlatformState.notifyStateChanged() }, 300L
        )
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：锁态翻转 false + 立即广播，
     * 扩展开工前查 state 拿 locked=false。
     * 消费方：UnlockSession.completeUnlock（密码/生物识别两条解锁路径的汇合点）。
     */
    fun onUnlocked() {
        ExtensionPlatformState.locked = false
        ExtensionPlatformState.notifyStateChanged()
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：注册归还钩子（真 lockApp：
     * 清 DEK/撤填充桥），扩展页 releaseSession 到达时执行。
     * 消费方：UnlockSession init（会话建立时注册）。
     */
    fun registerReleaseHook(hook: () -> Unit) {
        ExtensionPlatformState.releaseHook = hook
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：ON_STOP 是否触发锁定的让渡判定直通
     * （借出未超时 = 不锁，借出超时 = 恢复锁定并清标志）。
     * 消费方：UnlockSession.onAppStop。
     */
    fun shouldLockOnStop(nowMs: Long): Boolean = ExtensionPlatformState.shouldLockOnStop(nowMs)

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：导出空闲锚点给扩展 touchSession 心跳
     * （两端共享同一钟面，到点同秒）。
     * 消费方：IdleLockHost（IdleLock 构造参数 anchorExport，start/touch 时刷新）。
     */
    fun exportIdleAnchor(nowMs: Long) {
        ExtensionPlatformState.idleAnchorMs = nowMs
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：挂载扩展心跳 touch 钩子——provider 收到
     * touchSession 后 post 回主线程调用，扩展页活动计入本体同一把空闲锁。
     * 消费方：IdleLockHost（DisposableEffect 组装期）。
     */
    fun registerIdleHooks(touchHook: () -> Unit) {
        ExtensionPlatformState.idleTouchHook = touchHook
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：摘除扩展心跳 touch 钩子（防泄漏）。
     * 消费方：IdleLockHost（DisposableEffect onDispose）。
     */
    fun unregisterIdleHooks() {
        ExtensionPlatformState.idleTouchHook = null
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：导出自动锁定超时秒数给扩展本地倒计时
     * （0 = 关闭/未解锁，扩展侧见 0 即不武装本地看门狗）。
     * 消费方：IdleLockHost（LaunchedEffect，空闲锁启停时同步）。
     */
    fun updateIdleTimeoutSec(sec: Int) {
        ExtensionPlatformState.idleTimeoutSec = sec
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：解锁后回跳扩展页——消费一次性标记
     * （consumeResumeExtension 取走即清、2 分钟窗口），有效 → 借出会话并直接落回
     * 扩展页（launchExtensionSettings 内含 borrow）；无标记/过期/无扩展 → false，
     * 调用方维持 returnPage 原语义。
     * 消费方：AccountApp（rememberUnlockSession 的 onUnlocked 回调）。
     */
    fun consumeResumeAndLaunch(context: Context): Boolean {
        val consumed = ExtensionPlatformState.consumeResumeExtension(android.os.SystemClock.uptimeMillis())
        if (consumed) launchExtensionSettings(context)
        return consumed
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：平台状态广播的 context 注入
     * （一次性；组合期主线程安全）。
     * 消费方：AccountApp（组合期 remember 注入 applicationContext）。
     */
    fun attachNotifyContext(context: Context) {
        ExtensionPlatformState.notifyContext = context
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：会话让渡显式归还（标志清零；锁定本身
     * 由扩展侧 ON_STOP 回锁负责，这里只清让渡态）。
     * 消费方：AccountApp（ON_RESUME 生命周期观察者）。
     */
    fun onSessionEnd() {
        ExtensionPlatformState.releaseSession()
    }

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：在装扩展查询（按 action 解析，零包名
     * 硬编码），返回 null = 无在装扩展。调用方顺手以 [setExtensionPackage] 缓存包名。
     * 消费方：AccountApp（SettingsScreen 的 extensionLaunch 入参现查）。
     */
    fun resolveLaunchIntent(context: Context): Intent? = resolveExtensionSettingsIntent(context)

    /**
     * Wiring 收口（宿主不直接触碰 ExtensionPlatformState，公开/私有两侧以不同实现共存，
     * 公开版直通/空实现，保证 merge 零冲突）：缓存扩展包名——状态变化定向广播的收件人
     * （隐式广播被 ColorOS 丢弃，必须定向）；null = 无在装扩展 = 不发。
     * 消费方：AccountApp（resolveLaunchIntent 结果的 also 块）。
     */
    fun setExtensionPackage(pkg: String?) {
        ExtensionPlatformState.extensionPackage = pkg
    }
}
