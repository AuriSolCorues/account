/**
 * 职责：受控剪贴板——复制即提示；敏感内容到时自动清除，且只清「仍是自己那份数据」的剪贴板。
 * 架构位置：各页面的复制回调统一走这里；清除秒数来自 AppSettings.clipboardClearSeconds。
 * Python 类比：Android 剪贴板是系统级单例服务（getSystemService 取得），无需声明任何权限，
 *           ≈ 一个全系统共享的 paste buffer；清除前比对内容，防止误删用户之后复制的新东西。
 */
package com.copy.account.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** 写入剪贴板并只在内容仍未被用户替换时自动清除。Android 不需要申请剪贴板权限。 */
internal fun copyToClipboard(context: Context, text: String, sensitive: Boolean, clearAfterSeconds: Int) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("account", text))
    // 复制即提示只在这一处触发；Toast 是系统悬浮窗，能盖在底部面板之上。
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    // Handler(Looper.getMainLooper()).postDelayed ≈ 往主线程的事件循环里挂一个延迟回调
    // （≈ loop.call_later(seconds, fn)）；剪贴板与 Toast 都只能在主线程访问。
    if (sensitive && clearAfterSeconds > 0) Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == text) clipboard.clearPrimaryClip()
    }, clearAfterSeconds * 1_000L.toLong())
}
