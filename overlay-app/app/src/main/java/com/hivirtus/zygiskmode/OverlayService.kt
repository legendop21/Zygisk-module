package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!LicenseManager.isLicensed(this)) {
            stopSelf()
            return
        }
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            showOverlay()
            startOtpPolling()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        try {
            rootBinding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun showOverlay() {
        val root = OverlayRootBinding.inflate(LayoutInflater.from(this))
        val menu = root.menuPanel
        rootBinding = root
        menuBinding = menu

        setupMenu(menu)

        val metrics: DisplayMetrics = resources.displayMetrics
        layoutParams = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(root.root, layoutParams)
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

        menu.btnClose.setOnClickListener { stopSelf() }

        menu.tabSystem.setOnClickListener { selectTab(menu, Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(menu, Tab.MESSAGE) }
        menu.tabTelegram.setOnClickListener { selectTab(menu, Tab.TELEGRAM) }

        setupUpiApps(menu, config)

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

        selectTab(menu, Tab.SYSTEM)
    }

    private fun setupUpiApps(menu: OverlayMenuBinding, config: ModuleConfig) {
        menu.switchHookUpiVerification.isChecked = config.hookUpiVerification
        menu.upiAppsContainer.removeAllViews()
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
            menu.upiAppsContainer.addView(switch)
        }
    }

    private fun updateDeviceIdDisplay(menu: OverlayMenuBinding, id: String? = null) {
        val displayId = id ?: deviceIdManager.getCurrentSpoofId()
        menu.tvDeviceId.text = getString(R.string.device_id_label, displayId)
    }

    private enum class Tab { SYSTEM, MESSAGE, TELEGRAM }

    private fun selectTab(menu: OverlayMenuBinding, tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) android.view.View.VISIBLE else android.view.View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) android.view.View.VISIBLE else android.view.View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TELEGRAM) android.view.View.VISIBLE else android.view.View.GONE

        val active = ContextCompat.getColor(this, R.color.tab_active)
        val inactive = ContextCompat.getColor(this, R.color.tab_inactive)

        listOf(
            menu.tabSystem to Tab.SYSTEM,
            menu.tabMessage to Tab.MESSAGE,
            menu.tabTelegram to Tab.TELEGRAM
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
