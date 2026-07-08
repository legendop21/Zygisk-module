package com.hivirtus.zygiskmode

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * Dusre phone (+91) se aaya OTP — hooked UPI apps ko saved Sender ID ke saath
 * dubara inbox mein daalo taaki auto-read / sender match kaam kare.
 */
object SmsSenderRewriter {

    private const val TAG = "SmsSenderRewriter"
    private val recentKeys = mutableSetOf<String>()

    fun shouldRewrite(actualPeer: String, config: ModuleConfig): Boolean {
        val senderId = SmsMatcher.userSenderId(config) ?: return false
        if (senderId.isBlank()) return false
        if (!SmsMatcher.isIndianMobileNumber(actualPeer) && !SmsMatcher.isNumericSender(actualPeer)) {
            return false
        }
        return SmsMatcher.enabledHookedApps(config).isNotEmpty()
    }

    fun rewriteForHookedApps(context: Context, config: ModuleConfig, actualPeer: String, body: String) {
        if (!shouldRewrite(actualPeer, config)) return
        val senderId = SmsMatcher.userSenderId(config) ?: return
        if (body.isBlank()) return

        val key = "${actualPeer.trim()}|${body.trim()}"
        synchronized(recentKeys) {
            if (!recentKeys.add(key)) return
            if (recentKeys.size > 48) recentKeys.clear()
        }

        val inserted = insertInboxSms(context, senderId, body)
        ConfigManager(context).writeInjectCommand(senderId, body)
        Log.i(TAG, "Rewrote +91 SMS as Sender ID=$senderId inserted=$inserted")
    }

    private fun insertInboxSms(context: Context, senderId: String, body: String): Boolean {
        if (insertViaContentResolver(context, senderId, body)) return true
        return insertViaSu(senderId, body)
    }

    private fun insertViaContentResolver(context: Context, senderId: String, body: String): Boolean {
        if (!PermissionHelper.hasReadSms(context)) return false
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, senderId)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
            }
            val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            uri != null
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver insert failed: ${e.message}")
            false
        }
    }

    private fun insertViaSu(senderId: String, body: String): Boolean {
        val safeSender = senderId.replace("\"", "\\\"").replace("'", "'\\''")
        val safeBody = body.replace("\"", "\\\"").replace("'", "'\\''").replace("\n", " ")
        val date = System.currentTimeMillis()
        val cmd = buildString {
            append("content insert --uri content://sms/inbox ")
            append("--bind address:s:\"$safeSender\" ")
            append("--bind body:s:\"$safeBody\" ")
            append("--bind type:i:1 ")
            append("--bind read:i:0 ")
            append("--bind seen:i:0 ")
            append("--bind date:l:$date")
        }
        return ShellHelper.runSu(cmd)
    }
}
