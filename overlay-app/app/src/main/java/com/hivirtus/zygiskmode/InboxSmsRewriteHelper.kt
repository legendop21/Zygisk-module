package com.hivirtus.zygiskmode

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

/**
 * Google Messages inbox se sender padhta hai (JK-AXISBK-S) — native JNI hook kaam nahi karta.
 * Har incoming SMS ko app me set Sender ID (VM-YESBNK-S) me rewrite karo.
 */
object InboxSmsRewriteHelper {

    private const val TAG = "InboxSmsRewrite"
    private const val SCAN_LIMIT = 24

    private var observer: ContentObserver? = null
    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    fun startObserver(context: Context) {
        if (started) return
        if (!PermissionHelper.smsReady(context)) return
        started = true
        val app = context.applicationContext
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.postDelayed({ rewriteRecentInbox(app) }, 120L)
            }
        }
        try {
            app.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer!!
            )
            rewriteRecentInbox(app)
            Log.i(TAG, "Inbox sender rewrite observer active")
        } catch (e: Exception) {
            Log.w(TAG, "Observer register failed: ${e.message}")
            started = false
        }
    }

    fun stopObserver(context: Context) {
        observer?.let {
            try {
                context.applicationContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        observer = null
        started = false
    }

    /** Sender ID save / menu change pe purane inbox messages bhi rewrite. */
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
