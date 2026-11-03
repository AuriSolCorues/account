package com.copy.account.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal fun copyToClipboard(context: Context, text: String, sensitive: Boolean, clearAfterSeconds: Int) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("account", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    if (sensitive && clearAfterSeconds > 0) Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == text) clipboard.clearPrimaryClip()
    }, clearAfterSeconds * 1_000L.toLong())
}
