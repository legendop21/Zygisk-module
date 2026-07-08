package com.hivirtus.zygiskmode

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * +91 / numeric sender ko saved Sender ID (JK-AXISBK-S) mein rewrite —
 * notification + UPI OTP auto-read ke liye.
 */
object SmsSenderRewriter {

    private const val TAG = "SmsSenderRewriter"
    private val recentKeys = mutableSetOf<String>()

    fun shouldRewrite(actualPeer: String, config: ModuleConfig): Boolean {
        if (!config.overrideIncomingSender) return false
        if (!config.hookIncomingSms && !config.hookUpiVerification) return false
        val senderId = SmsMatcher.userSenderId(config) ?: return false
        if (senderId.isBlank()) return false
        if (!SmsMatcher.isIndianMobileNumber(actualPeer) && !SmsMatcher.isNumericSender(actualPeer)) {
            return false
        }
        return true
    }

    fun rewriteIncomingSender(
        context: Context,
        config: ModuleConfig,
        actualPeer: String,
        body: String,
        messageId: Long = -1L
    ): Boolean {
        if (!shouldRewrite(actualPeer, config)) return false
        val senderId = SmsMatcher.userSenderId(config) ?: return false
        if (body.isBlank()) return false

        val key = "${actualPeer.trim()}|${body.trim()}|rewrite"
        synchronized(recentKeys) {
            if (!recentKeys.add(key)) return false
            if (recentKeys.size > 64) recentKeys.clear()
        }

        val deleted = deleteInboxSms(context, messageId, actualPeer, body)
        val inserted = insertInboxSms(context, senderId, body)
        ConfigManager(context).writeInjectCommand(senderId, body)
        try {
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Rewrote $actualPeer -> $senderId deleted=$deleted inserted=$inserted")
        return inserted
    }

    /** MESSAGE tab inject — seedha Sender ID se inbox mein daalo */
    fun injectInboxMessage(context: Context, senderId: String, body: String): Boolean {
        if (senderId.isBlank() || body.isBlank()) return false
        val inserted = insertInboxSms(context, senderId.trim(), body.trim())
        ConfigManager(context).writeInjectCommand(senderId.trim(), body.trim())
        try {
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Injected inbox SMS from $senderId inserted=$inserted")
        return inserted
    }

    @Deprecated("Use rewriteIncomingSender", ReplaceWith("rewriteIncomingSender(context, config, actualPeer, body)"))
    fun rewriteForHookedApps(context: Context, config: ModuleConfig, actualPeer: String, body: String) {
        rewriteIncomingSender(context, config, actualPeer, body)
    }

    private fun deleteInboxSms(context: Context, messageId: Long, peer: String, body: String): Boolean {
        if (messageId > 0L) {
            try {
                val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId)
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) return true
            } catch (e: Exception) {
                Log.w(TAG, "Delete by id failed: ${e.message}")
            }
        }

        if (PermissionHelper.hasReadSms(context)) {
            try {
                val deleted = context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.TYPE}=?",
                    arrayOf(peer, body, Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
                )
                if (deleted > 0) return true
            } catch (e: Exception) {
                Log.w(TAG, "Delete by query failed: ${e.message}")
            }
        }

        return deleteViaSu(peer, body)
    }

    private fun deleteViaSu(peer: String, body: String): Boolean {
        val safePeer = peer.replace("'", "'\\''")
        val safeBody = body.replace("'", "'\\''").replace("\n", " ")
        return ShellHelper.runSu(
            "content delete --uri content://sms/inbox " +
                "--where \"address='$safePeer' AND body='$safeBody'\""
        )
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
