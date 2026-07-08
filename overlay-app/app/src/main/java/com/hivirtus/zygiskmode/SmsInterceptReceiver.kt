package com.hivirtus.zygiskmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

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

            val configManager = ConfigManager(context)
            val config = configManager.load()
            if (!SmsMatcher.shouldCaptureIncoming(config, sender, body)) return

            val interceptDisplay = SmsMatcher.interceptDisplay(config, sender, configManager)
            val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
            val otp = LastOtp(
                otp = token,
                sender = interceptDisplay,
                body = body,
                phone = interceptDisplay,
                messageLabel = SmsMatcher.matchedHookedApp(config, sender, body)?.displayName.orEmpty(),
                direction = "incoming",
                rawPeer = sender
            )
            OtpCaptureWriter.write(context, otp)
            OtpAutoFillHelper.onHookedOtpCaptured(context, config, otp)
            if (SmsSenderRewriter.shouldRewrite(sender, config)) {
                SmsSenderRewriter.rewriteForHookedApps(context, config, sender, body)
            }
            if (SmsMatcher.shouldForwardToTelegram(config, sender, body, "incoming")) {
                context.sendBroadcast(
                    Intent(ACTION_SMS_CAPTURED).setPackage(context.packageName)
                )
                try {
                    abortBroadcast()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS intercept failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_SMS_CAPTURED = "com.hivirtus.zygiskmode.SMS_CAPTURED"
        private const val TAG = "SmsInterceptReceiver"
    }
}
