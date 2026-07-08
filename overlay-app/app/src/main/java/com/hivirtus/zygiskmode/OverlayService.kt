package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private val bubbleManager by lazy { FloatingBubbleManager(this) }
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bubbleManager.hide()
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
        }

        if (!ModuleGate.isModuleFlashed()) {
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
        bubbleManager.hide()
        super.onDestroy()
    }

    private fun ensureRunning(showBubble: Boolean) {
        if (!running) running = true
        if (showBubble) bubbleManager.show()
    }

    private fun createNotification(): Notification {
        val channelId = "hivirtus_zygisk_edu_lab"
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

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.mod_menu_title))
            .setContentText(getString(R.string.bubble_always_on))
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
        private const val NOTIFICATION_ID = 2001
    }
}
