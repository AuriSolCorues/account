/**
 * 职责：本体的可选扩展门面——扩展模块的在装发现（action 解析，零包名硬编码）、
 *       设置页拉起。本体零联网红线不受影响：
 *       这里只有 PackageManager 查询，触网发生在扩展进程。
 * 架构位置：runtime/ 层；SettingsScreen（入口行）消费。
 * Python 类比：≈ 一个扩展模块的 host SDK——discover() 与 launch()。
 */
package com.copy.account.runtime

import android.content.Context
import android.content.Intent

/** 扩展桥契约的平台侧部分（action/指针文件名——字符串即契约）。 */
object ExtensionContract {
    /** 扩展设置页的 action（扩展 intent-filter 声明；本体按此发现在装扩展）。 */
    const val ACTION_SETTINGS = "com.copy.account.action.SYNC_SETTINGS"
    const val POINTER_FILE_NAME = ".account-sync.json"
}

/**
 * 在装扩展查询（设置页「扩展」入口行的显示条件）。
 * 按 action 解析，不硬编码任何扩展包名——公开仓库零具体扩展字样。
 * 返回 null = 无扩展在装；非 null = 首个响应扩展的拉起 Intent（可直接 startActivity）。
 */
fun resolveExtensionSettingsIntent(context: Context): Intent? =
    context.packageManager.queryIntentActivities(
        Intent(ExtensionContract.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
        0
    ).firstOrNull()?.let { info ->
        Intent(ExtensionContract.ACTION_SETTINGS)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

/**
 * 拉起扩展设置页（会话让渡的唯一起点）：解析 intent → 借出会话 → startActivity。
 * borrow 必须先行——本体 ON_STOP 是「让渡」不是「离开」，否则拉起瞬间就被锁定链路回锁。
 * 两处复用：设置页「扩展」行、解锁后回跳扩展页（consumeResumeExtension 消费点）。
 * 返回 false = 无扩展在装，调用方自行兜底。
 */
fun launchExtensionSettings(context: Context): Boolean {
    val intent = resolveExtensionSettingsIntent(context) ?: return false
    ExtensionPlatformState.borrowSession(android.os.SystemClock.uptimeMillis())
    return runCatching { context.startActivity(intent) }.isSuccess
}
