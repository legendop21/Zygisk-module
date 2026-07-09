package com.hivirtus.zygiskmode

import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * Google Messages inbox `content://sms` ADDRESS field — native hook se nahi badalta.
 * Rewrite sirf jab SMS aaye (broadcast) ya menu se Sender ID save ho.
 */
object InboxSmsRewriteHelper {

    private const val TAG = "InboxSmsRewrite"
    private const val SCAN_LIMIT = 24

    /** Legacy no-op — pehle always-on observer tha (battery drain). */
    fun startObserver(context: Context) {
        // No-op: SMS rewrite is broadcast-driven only.
    }

    fun stopObserver(context: Context) {
        // No-op
    }

    /** SMS_RECEIVED broadcast — sirf naya message, poora inbox scan nahi. */
    fun onIncomingSms(context: Context, actualPeer: String, body: String) {
        if (!PermissionHelper.smsReady(context)) return
        val app = context.applicationContext
        val config = ConfigManager(app).load()
        if (!SmsSenderRewriter.shouldRewrite(actualPeer, config)) return
        SmsSenderRewriter.rewriteIncomingSender(app, config, actualPeer, body)
    }

    /** Sender ID menu save — ek baar last 24 messages (optional). */
    fun rewriteRecentInbox(context: Context) {
        if (!PermissionHelper.smsReady(context)) return
        val app = context.applicationContext
        val config = ConfigManager(app).load()
        val senderId = SmsMatcher.userSenderId(config) ?: return
        if (!config.overrideIncomingSender) return

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY
            )
            app.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE}=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT $SCAN_LIMIT"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val peer = cursor.getString(1)?.trim().orEmpty()
                    val body = cursor.getString(2)?.trim().orEmpty()
                    if (peer.isBlank() || body.isBlank()) continue
                    if (peer.equals(senderId, ignoreCase = true)) continue
                    if (!SmsSenderRewriter.shouldRewrite(peer, config)) continue
                    SmsSenderRewriter.rewriteIncomingSender(app, config, peer, body, id)
                }
            }
            try {
                app.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "rewriteRecentInbox failed: ${e.message}")
        }
    }
}
