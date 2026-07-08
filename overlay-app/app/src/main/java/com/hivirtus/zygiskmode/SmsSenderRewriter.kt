package com.hivirtus.zygiskmode

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

/**
 * +91 / numeric sender ko saved Sender ID (JK-AXISBK-S) mein rewrite —
 * notification + UPI OTP auto-read ke liye.
 */
object SmsSenderRewriter {

    private const val TAG = "SmsSenderRewriter"
    private val recentKeys = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    fun shouldRewrite(actualPeer: String, config: ModuleConfig): Boolean {
        if (!config.overrideIncomingSender) return false
        val senderId = SmsMatcher.userSenderId(config) ?: return false
        if (senderId.isBlank()) return false
        // LSPosed-style: +91 / numeric / koi bhi incoming → saved sender ID (notification)
        val actual = actualPeer.trim()
        if (actual.equals(senderId, ignoreCase = true)) return false
        return SmsMatcher.isIndianMobileNumber(actual) ||
            SmsMatcher.isNumericSender(actual) ||
            actual.isNotBlank()
    }

    /**
     * SMS_RECEIVED pe — system ko +91 save karne se roko, seedha Sender ID insert karo.
     */
    fun rewriteOnReceive(context: Context, config: ModuleConfig, actualPeer: String, body: String): Boolean {
        if (!shouldRewrite(actualPeer, config)) return false
        val senderId = SmsMatcher.userSenderId(config) ?: return false
        if (body.isBlank()) return false

        val appContext = context.applicationContext
        val inserted = insertInboxSms(appContext, senderId, body)
        ConfigManager(appContext).writeInjectCommand(senderId, body)
        scheduleInboxCleanup(appContext, actualPeer, body, senderId)
        try {
            appContext.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Receive rewrite $actualPeer -> $senderId inserted=$inserted")
        return inserted
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

        val key = "${actualPeer.trim()}|${body.trim()}|inbox"
        synchronized(recentKeys) {
            if (!recentKeys.add(key)) return false
            if (recentKeys.size > 128) recentKeys.clear()
        }

        val deleted = deleteInboxSms(context, messageId, actualPeer, body)
        val inserted = insertInboxSms(context, senderId, body)
        if (!inserted) {
            scheduleInboxCleanup(context.applicationContext, actualPeer, body, senderId)
        }
        ConfigManager(context).writeInjectCommand(senderId, body)
        try {
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Inbox rewrite $actualPeer -> $senderId deleted=$deleted inserted=$inserted")
        return inserted || deleted
    }

    fun injectInboxMessage(context: Context, senderId: String, body: String): Boolean {
        if (senderId.isBlank() || body.isBlank()) return false
        val inserted = insertInboxSms(context, senderId.trim(), body.trim())
        ConfigManager(context).writeInjectCommand(senderId.trim(), body.trim())
        TelephonyInjectHelper.wakeTelephonyPipeline()
        try {
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Injected inbox SMS from $senderId inserted=$inserted")
        return inserted
    }

    private fun scheduleInboxCleanup(
        context: Context,
        actualPeer: String,
        body: String,
        senderId: String
    ) {
        val delays = longArrayOf(350L, 900L, 2000L, 4500L)
        for (delay in delays) {
            handler.postDelayed({
                try {
                    deleteInboxSms(context, -1L, actualPeer, body)
                    deletePeerVariants(context, actualPeer, body)
                    if (!inboxHasSenderMessage(context, senderId, body)) {
                        insertInboxSms(context, senderId, body)
                    }
                    context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup delayed failed: ${e.message}")
                }
            }, delay)
        }
    }

    private fun deletePeerVariants(context: Context, peer: String, body: String) {
        val digits = peer.replace(Regex("[^0-9]"), "")
        val variants = linkedSetOf(peer, digits)
        if (digits.length == 10) {
            variants += digits
            variants += "+91$digits"
            variants += "91$digits"
            variants += "0$digits"
        } else if (digits.length == 12 && digits.startsWith("91")) {
            variants += digits.substring(2)
            variants += "+$digits"
        }
        variants.forEach { variant ->
            if (variant.isNotBlank()) {
                deleteInboxSms(context, -1L, variant, body)
            }
        }
    }

    private fun inboxHasSenderMessage(context: Context, senderId: String, body: String): Boolean {
        if (!PermissionHelper.hasReadSms(context)) return false
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.TYPE}=?",
                arrayOf(senderId, body, Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                null
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }
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
