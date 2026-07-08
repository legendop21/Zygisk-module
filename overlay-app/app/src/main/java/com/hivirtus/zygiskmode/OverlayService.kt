package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    private val tokenForwarder by lazy { TokenForwarder(configManager) }
    private val bubbleManager by lazy { FloatingBubbleManager(this) }
    private var pollJob: Job? = null
    private var lastForwardedKey: String? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bubbleManager.hide()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                ensureRunning()
                bubbleManager.show()
                if (!bubbleManager.isShowing()) {
                    Toast.makeText(this, R.string.bubble_need_overlay, Toast.LENGTH_SHORT).show()
                }
                return START_STICKY
            }
            ACTION_HIDE_BUBBLE -> {
                bubbleManager.hide()
                return START_STICKY
            }
        }

        if (!LicenseManager.isLicensed(this)) {
            Toast.makeText(this, R.string.license_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureRunning()
        bubbleManager.hide()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pollJob?.cancel()
        bubbleManager.hide()
        super.onDestroy()
    }

    private fun ensureRunning() {
        if (!running) {
            running = true
            startOtpPolling()
        }
    }

    private fun startOtpPolling() {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val lastOtp = configManager.readLastOtp()
                    if (lastOtp != null) {
                        val forwardKey = "${lastOtp.sender}|${lastOtp.body}|${lastOtp.otp}"
                        if (forwardKey != lastForwardedKey && tokenForwarder.forward(lastOtp)) {
                            lastForwardedKey = forwardKey
                        }
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
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

        val bubbleIntent = Intent(this, OverlayService::class.java).setAction(ACTION_SHOW_BUBBLE)
        val bubblePending = PendingIntent.getService(
            this, 2, bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.mod_menu_title))
            .setContentText(getString(R.string.tap_notification_open_menu))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .addAction(0, getString(R.string.open_menu), pending)
            .addAction(0, getString(R.string.show_bubble), bubblePending)
            .addAction(0, getString(R.string.stop_overlay), stopPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.hivirtus.zygiskmode.STOP"
        const val ACTION_OPEN = "com.hivirtus.zygiskmode.OPEN"
        const val ACTION_SHOW_BUBBLE = "com.hivirtus.zygiskmode.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.hivirtus.zygiskmode.HIDE_BUBBLE"
        private const val NOTIFICATION_ID = 2001
    }
}
