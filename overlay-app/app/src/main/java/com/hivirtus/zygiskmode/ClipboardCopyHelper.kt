package com.hivirtus.zygiskmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardCopyHelper {

    fun copySms(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("hivirtus_sms", text))
            true
        } catch (_: Exception) {
            false
        }
    }
}
