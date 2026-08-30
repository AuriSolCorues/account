package com.copy.account.core.security

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
    if (sensitive && clearAfterSeconds > 0) Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == text) clipboard.clearPrimaryClip()
    }, clearAfterSeconds * 1_000L.toLong())
}
