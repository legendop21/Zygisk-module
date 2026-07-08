package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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

    private var windowManager: WindowManager? = null
    private var rootBinding: OverlayRootBinding? = null
    private var menuBinding: OverlayMenuBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val configManager by lazy { ConfigManager(this) }
    private val tokenForwarder by lazy { TokenForwarder(configManager) }
    private val backupManager by lazy { BackupManager(this) }
    private val deviceIdManager by lazy { DeviceIdManager(this) }
    private var pollJob: Job? = null
    private var lastForwardedKey: String? = null
    private val upiAppSwitches = mutableMapOf<String, SwitchMaterial>()
    private var menuOpen = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LicenseManager.isLicensed(this)) {
            Toast.makeText(this, R.string.license_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.grant_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootBinding == null) {
            try {
                showOverlay()
                startOtpPolling()
                Handler(Looper.getMainLooper()).postDelayed({ openMenu() }, 300)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        } else if (intent?.action == ACTION_OPEN) {
            openMenu()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        try {
            rootBinding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        rootBinding = null
        menuBinding = null
        super.onDestroy()
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = OverlayRootBinding.inflate(LayoutInflater.from(this))
        val menu = root.menuPanel
        rootBinding = root
        menuBinding = menu

        setupMenu(menu)
        setupBubble(root)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 280
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager?.addView(root.root, layoutParams)
        collapseMenu()
    }

    private fun setupBubble(root: OverlayRootBinding) {
        root.floatingBubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (dx * dx + dy * dy > 36) isDragging = true
                    root.floatingBubble.translationX = dx.toFloat()
                    root.floatingBubble.translationY = dy.toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (menuOpen) collapseMenu() else openMenu()
                    } else {
                        root.floatingBubble.translationX = root.floatingBubble.translationX
                        root.floatingBubble.translationY = root.floatingBubble.translationY
                    }
                    true
                }
                else -> false
            }
        }

        root.dimBackground.setOnClickListener { collapseMenu() }
    }

    private fun openMenu() {
        val root = rootBinding ?: return
        menuOpen = true
        root.fullScreenLayer.visibility = View.VISIBLE
        root.floatingBubble.visibility = View.GONE
        updateWindowExpanded(true)
    }

    private fun collapseMenu() {
        val root = rootBinding ?: return
        menuOpen = false
        root.fullScreenLayer.visibility = View.GONE
        root.floatingBubble.visibility = View.VISIBLE
        root.floatingBubble.translationX = 0f
        root.floatingBubble.translationY = 0f
        updateWindowExpanded(false)
    }

    private fun updateWindowExpanded(expanded: Boolean) {
        val lp = layoutParams ?: return
        val root = rootBinding ?: return
        if (expanded) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            lp.x = 0
            lp.y = 0
            menuBinding?.let { selectTab(it, Tab.UPI) }
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 24
            lp.y = 280
        }
        windowManager?.updateViewLayout(root.root, lp)
    }

    private fun setupMenu(menu: OverlayMenuBinding) {
        val config = configManager.load()

        menu.switchSim1Mock.isChecked = config.enableSim1Mock
        menu.switchSim2Mock.isChecked = config.enableSim2Mock
        menu.etCountryIso.setText(config.mockCountryIso)
        menu.switchHideMagisk.isChecked = config.hideMagisk
        menu.switchHideKernelSu.isChecked = config.hideKernelSu
        menu.switchHideApatch.isChecked = config.hideApatch
        menu.switchHideSukisu.isChecked = config.hideSukisu
        menu.switchHideAllRootApps.isChecked = config.hideAllRootApps
        menu.switchNotDeveloper.isChecked = config.hideDeveloper
        menu.switchNotRoot.isChecked = config.hideRoot
        menu.switchPhoneSpoof.isChecked = config.enablePhoneSpoof
        menu.etPhoneSim1.setText(config.mockPhoneSim1)
        menu.etPhoneSim2.setText(config.mockPhoneSim2)
        updateDeviceIdDisplay(menu)
        menu.switchHookIncoming.isChecked = config.hookIncomingSms
        menu.switchHookOutgoing.isChecked = config.hookOutgoingSms
        menu.etSenderId.setText(config.injectSenderId)
        menu.etMessageBody.setText(config.injectMessageBody)
        menu.switchAutoForward.isChecked = config.autoForwardToken
        menu.etBotToken.setText(config.telegramBotToken)
        menu.etChatId.setText(config.telegramChatId)
        menu.switchHookUpiVerification.isChecked = config.hookUpiVerification

        setupUpiApps(menu, config)

        menu.btnClose.setOnClickListener { collapseMenu() }

        menu.tabSystem.setOnClickListener { selectTab(menu, Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(menu, Tab.MESSAGE) }
        menu.tabUpi.setOnClickListener { selectTab(menu, Tab.UPI) }
        menu.tabTelegram.setOnClickListener { selectTab(menu, Tab.TG) }

        menu.btnSaveSim.setOnClickListener {
            val updated = configManager.load().copy(
                enableSim1Mock = menu.switchSim1Mock.isChecked,
                enableSim2Mock = menu.switchSim2Mock.isChecked,
                mockCountryIso = menu.etCountryIso.text?.toString()?.lowercase()?.ifBlank { "in" } ?: "in",
                hideMagisk = menu.switchHideMagisk.isChecked,
                hideKernelSu = menu.switchHideKernelSu.isChecked,
                hideApatch = menu.switchHideApatch.isChecked,
                hideSukisu = menu.switchHideSukisu.isChecked,
                hideAllRootApps = menu.switchHideAllRootApps.isChecked,
                hideDeveloper = menu.switchNotDeveloper.isChecked,
                hideRoot = menu.switchNotRoot.isChecked,
                enablePhoneSpoof = menu.switchPhoneSpoof.isChecked,
                mockPhoneSim1 = menu.etPhoneSim1.text?.toString()?.ifBlank { "+919876543210" } ?: "+919876543210",
                mockPhoneSim2 = menu.etPhoneSim2.text?.toString()?.ifBlank { "+919876543211" } ?: "+919876543211"
            )
            configManager.save(updated)
            Toast.makeText(this, R.string.sim_settings_saved, Toast.LENGTH_SHORT).show()
        }

        menu.btnBackupNow.setOnClickListener {
            val path = backupManager.createBackup()
            Toast.makeText(
                this,
                if (path != null) R.string.backup_created else R.string.backup_failed,
                Toast.LENGTH_SHORT
            ).show()
        }

        menu.btnRestoreBackup.setOnClickListener {
            val ok = backupManager.restoreLatest()
            if (ok) {
                setupMenu(menu)
                Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.backup_restore_failed, Toast.LENGTH_SHORT).show()
            }
        }

        menu.btnChangeDeviceId.setOnClickListener {
            val newId = deviceIdManager.generateNewAndroidId()
            updateDeviceIdDisplay(menu, newId)
            backupManager.createBackup()
            Toast.makeText(this, R.string.device_id_changed, Toast.LENGTH_SHORT).show()
        }

        menu.btnInjectSms.setOnClickListener {
            val sender = menu.etSenderId.text?.toString()?.ifBlank { "AD-TEST-S" } ?: "AD-TEST-S"
            val body = menu.etMessageBody.text?.toString().orEmpty()
            if (body.isBlank()) {
                Toast.makeText(this, R.string.enter_message_body, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            configManager.update {
                it.copy(
                    injectSenderId = sender,
                    injectMessageBody = body,
                    hookIncomingSms = menu.switchHookIncoming.isChecked,
                    hookOutgoingSms = menu.switchHookOutgoing.isChecked
                )
            }
            configManager.writeInjectCommand(sender, body)
            Toast.makeText(this, R.string.sms_injected, Toast.LENGTH_SHORT).show()
        }

        menu.btnSaveTelegram.setOnClickListener {
            val token = menu.etBotToken.text?.toString().orEmpty()
            val chatId = menu.etChatId.text?.toString().orEmpty()
            val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                "https://api.telegram.org/bot$token/sendMessage"
            } else {
                configManager.load().forwardUrl
            }
            configManager.update {
                it.copy(
                    autoForwardToken = menu.switchAutoForward.isChecked,
                    telegramBotToken = token,
                    telegramChatId = chatId,
                    forwardUrl = forwardUrl
                )
            }
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }

        menu.btnTestTelegram.setOnClickListener {
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

        menu.btnSelectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = true }
        }

        menu.btnDeselectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = false }
        }

        menu.btnSaveUpiHooks.setOnClickListener {
            val hooked = upiAppSwitches.mapValues { it.value.isChecked }
            configManager.update {
                it.copy(
                    hookUpiVerification = menu.switchHookUpiVerification.isChecked,
                    hookedUpiApps = hooked
                )
            }
            Toast.makeText(this, R.string.upi_hooks_saved, Toast.LENGTH_SHORT).show()
        }

        selectTab(menu, Tab.UPI)
    }

    private fun setupUpiApps(menu: OverlayMenuBinding, config: ModuleConfig) {
        menu.upiAppsContainer.removeAllViews()
        upiAppSwitches.clear()
        UpiAppRegistry.ALL.sortedBy { it.displayName.lowercase() }.forEach { app ->
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
            menu.upiAppsContainer.addView(switch)
        }
    }

    private fun updateDeviceIdDisplay(menu: OverlayMenuBinding, id: String? = null) {
        val displayId = id ?: deviceIdManager.getCurrentSpoofId()
        menu.tvDeviceId.text = getString(R.string.device_id_label, displayId)
    }

    private enum class Tab { SYSTEM, MESSAGE, UPI, TG }

    private fun selectTab(menu: OverlayMenuBinding, tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menu.panelUpi.visibility = if (tab == Tab.UPI) View.VISIBLE else View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TG) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.tab_active)
        val inactive = ContextCompat.getColor(this, R.color.tab_inactive)

        listOf(
            menu.tabSystem to Tab.SYSTEM,
            menu.tabMessage to Tab.MESSAGE,
            menu.tabUpi to Tab.UPI,
            menu.tabTelegram to Tab.TG
        ).forEach { (view, t) ->
            view.isSelected = tab == t
            view.setTextColor(if (tab == t) active else inactive)
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

        val openIntent = Intent(this, OverlayService::class.java).setAction(ACTION_OPEN)
        val pending = android.app.PendingIntent.getService(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.mod_menu_title))
            .setContentText(getString(R.string.tap_bubble_hint))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.hivirtus.zygiskmode.STOP"
        const val ACTION_OPEN = "com.hivirtus.zygiskmode.OPEN"
        private const val NOTIFICATION_ID = 2001
    }
}
