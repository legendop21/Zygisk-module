package com.hivirtus.zygiskmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Instant SMS intercept — works while OverlayService is alive */
class SmsInterceptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PermissionHelper.hasReadSms(context)) return

        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (parts.isEmpty()) return

            val sender = parts.firstOrNull()?.originatingAddress?.trim().orEmpty()
            val body = parts.joinToString("") { it.messageBody.orEmpty() }.trim()
            if (sender.isBlank() || body.isBlank()) return

            val config = ConfigManager(context).load()
            if (!SmsMatcher.shouldCapture(config, sender, body)) return

            val interceptNo = sender.replace("\\D".toRegex(), "").ifBlank { sender.trim() }
                .let { if (it.length >= 8) it else sender.trim() }
            val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
            val otp = LastOtp(
                otp = token,
                sender = interceptNo,
                body = body,
                phone = interceptNo,
                messageLabel = "",
                direction = "incoming"
            )
            OtpCaptureWriter.write(context, otp)
            context.sendBroadcast(
                Intent(ACTION_SMS_CAPTURED).setPackage(context.packageName)
            )
        } catch (e: Exception) {
            Log.w(TAG, "SMS intercept failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_SMS_CAPTURED = "com.hivirtus.zygiskmode.SMS_CAPTURED"
        private const val TAG = "SmsInterceptReceiver"
    }
}
