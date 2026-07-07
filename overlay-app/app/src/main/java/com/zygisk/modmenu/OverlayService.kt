package com.zygisk.modmenu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.zygisk.modmenu.databinding.OverlayMenuBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var binding: OverlayMenuBinding
    private val configManager by lazy { ConfigManager(this) }
    private val tokenForwarder by lazy { TokenForwarder(configManager) }
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        startOtpPolling()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    private fun showOverlay() {
        binding = OverlayMenuBinding.inflate(LayoutInflater.from(this))

        val config = configManager.load()
        binding.switchHookIncoming.isChecked = config.hookIncomingSms
        binding.switchHookOutgoing.isChecked = config.hookOutgoingSms
        binding.switchAutoOtp.isChecked = config.autoExtractOtp
        binding.switchAutoForward.isChecked = config.autoForwardToken
        binding.etSenderId.setText(config.injectSenderId)
        binding.etMessageBody.setText(config.injectMessageBody)
        binding.etForwardUrl.setText(config.forwardUrl)

        binding.btnClose.setOnClickListener { stopSelf() }

        binding.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        binding.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        binding.tabForward.setOnClickListener { selectTab(Tab.FORWARD) }

        binding.switchHookIncoming.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(hookIncomingSms = checked) }
        }
        binding.switchHookOutgoing.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(hookOutgoingSms = checked) }
        }
        binding.switchAutoOtp.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(autoExtractOtp = checked) }
        }
        binding.switchAutoForward.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(autoForwardToken = checked) }
        }

        binding.btnSaveForward.setOnClickListener {
            val url = binding.etForwardUrl.text?.toString().orEmpty()
            configManager.update { it.copy(forwardUrl = url, autoForwardToken = binding.switchAutoForward.isChecked) }
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }

        binding.btnInjectSms.setOnClickListener {
            val sender = binding.etSenderId.text?.toString().orEmpty().ifBlank { "AD-TEST-S" }
            val body = binding.etMessageBody.text?.toString().orEmpty()
            if (body.isBlank()) {
                Toast.makeText(this, R.string.enter_message_body, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            configManager.update {
                it.copy(
                    injectSenderId = sender,
                    injectMessageBody = body,
                    hookIncomingSms = binding.switchHookIncoming.isChecked,
                    hookOutgoingSms = binding.switchHookOutgoing.isChecked
                )
            }

            configManager.writeInjectCommand(sender, body)
            Toast.makeText(this, R.string.sms_injected, Toast.LENGTH_SHORT).show()
        }

        selectTab(Tab.MESSAGE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(binding.root, params)
        overlayView = binding.root
    }

    private enum class Tab { SYSTEM, MESSAGE, FORWARD }

    private fun selectTab(tab: Tab) {
        binding.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        binding.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        binding.panelForward.visibility = if (tab == Tab.FORWARD) View.VISIBLE else View.GONE

        val active = resources.getColor(R.color.tab_active, theme)
        val inactive = resources.getColor(R.color.tab_inactive, theme)

        binding.tabSystem.setTextColor(if (tab == Tab.SYSTEM) active else inactive)
        binding.tabMessage.setTextColor(if (tab == Tab.MESSAGE) active else inactive)
        binding.tabForward.setTextColor(if (tab == Tab.FORWARD) active else inactive)
    }

    private fun startOtpPolling() {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val lastOtp = configManager.readLastOtp()
                if (lastOtp != null) {
                    val config = configManager.load()
                    if (config.autoForwardToken) {
                        tokenForwarder.forward(lastOtp)
                    }
                    launch(Dispatchers.Main) {
                        binding.tvLastOtp.text = getString(R.string.last_otp_format, lastOtp.otp, lastOtp.sender)
                    }
                }
                delay(2000)
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "zygisk_mod_menu"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
