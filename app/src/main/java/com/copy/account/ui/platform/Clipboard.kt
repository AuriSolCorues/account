package com.copy.account.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.copy.account.R

internal fun copyToClipboard(context: Context, text: String, sensitive: Boolean, clearAfterSeconds: Int) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("account", text))
    Toast.makeText(context, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
    if (sensitive && clearAfterSeconds > 0) Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == text) clipboard.clearPrimaryClip()
    }, clearAfterSeconds * 1_000L.toLong())
}
