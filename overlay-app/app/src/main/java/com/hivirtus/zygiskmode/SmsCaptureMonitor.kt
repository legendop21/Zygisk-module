package com.hivirtus.zygiskmode

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

class SmsCaptureMonitor(
    private val context: Context,
    private val onCaptured: (LastOtp) -> Unit
) {
    private val configManager = ConfigManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedInId = -1L
    private var lastProcessedOutId = -1L
    private var running = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scanInbox()
            scanOutgoing()
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "SMS observer register failed: ${e.message}")
        }
        scanInbox()
        scanOutgoing()
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {}
    }

    fun processIncoming(sender: String, body: String): Boolean {
        if (sender.isBlank() || body.isBlank()) return false
        val config = configManager.load()
        if (!SmsMatcher.shouldCaptureIncoming(config, sender, body)) return false
        deliver(config, sender, body, "incoming")
        return SmsMatcher.shouldForwardToTelegram(config, sender, body, "incoming")
    }

    private fun scanInbox() {
        scanMessages(Telephony.Sms.MESSAGE_TYPE_INBOX, lastProcessedInId) { id, peer, body ->
            lastProcessedInId = id
            val config = configManager.load()
            if (SmsSenderRewriter.shouldRewrite(peer, config)) {
                if (SmsSenderRewriter.rewriteIncomingSender(context, config, peer, body, id)) {
                    return@scanMessages
                }
            }
            if (!SmsMatcher.shouldCaptureIncoming(config, peer, body)) return@scanMessages
            deliver(config, peer, body, "incoming")
        }
    }

    private fun scanOutgoing() {
        scanMessages(Telephony.Sms.MESSAGE_TYPE_SENT, lastProcessedOutId) { id, peer, body ->
            lastProcessedOutId = id
            val config = configManager.load()
            if (!SmsMatcher.shouldCaptureOutgoing(config, peer, body)) return@scanMessages
            deliver(config, peer, body, "outgoing")
        }
    }

    private fun scanMessages(
        type: Int,
        lastId: Long,
        onMatch: (Long, String, String) -> Unit
    ) {
        if (!PermissionHelper.hasReadSms(context)) return
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE}=?",
                arrayOf(type.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 12"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id <= lastId) continue
                    val peer = it.getString(1)?.trim().orEmpty()
                    val body = it.getString(2)?.trim().orEmpty()
                    if (peer.isBlank() || body.isBlank()) continue
                    onMatch(id, peer, body)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS scan failed: ${e.message}")
        }
    }

    private fun deliver(config: ModuleConfig, actualPeer: String, body: String, direction: String) {
        val isOutgoing = direction == "outgoing"
        val allowed = if (isOutgoing) {
            SmsMatcher.shouldInterceptOutgoing(config, actualPeer, body)
        } else {
            SmsMatcher.shouldInterceptIncoming(config, actualPeer, body)
        }
        if (!allowed) return

        val interceptDisplay = SmsMatcher.interceptDisplay(config, actualPeer, configManager)
        val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
        val label = SmsMatcher.matchedHookedApp(config, actualPeer, body)?.displayName.orEmpty()
        val now = System.currentTimeMillis()
        val otp = LastOtp(
            otp = token,
            sender = interceptDisplay,
            body = body,
            phone = interceptDisplay,
            messageLabel = label,
            direction = direction,
            rawPeer = actualPeer,
            capturedAt = now
        )
        OtpCaptureWriter.write(context, otp)
        OtpAutoFillHelper.onHookedOtpCaptured(context, config, otp)
        if (SmsMatcher.shouldForwardToTelegram(config, actualPeer, body, direction)) {
            onCaptured(otp)
        }
    }

    companion object {
        private const val TAG = "SmsCaptureMonitor"
    }
}
