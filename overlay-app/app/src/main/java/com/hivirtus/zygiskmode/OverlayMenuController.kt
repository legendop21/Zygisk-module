package com.hivirtus.zygiskmode

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayMenuController(
    private val context: Context,
    private val menu: OverlayMenuBinding,
    private val onMinimize: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configManager = ConfigManager(context)
    private val tokenForwarder = TokenForwarder(configManager, context.applicationContext)
    private val backupManager = BackupManager(context)
    private val deviceIdManager = DeviceIdManager(context)
    private val upiAppSwitches = mutableMapOf<String, SwitchMaterial>()
    private var listenersAttached = false

    fun bind() {
        refreshFields()
        setupRootHideToggles()
        if (!listenersAttached) {
            setupClickListeners()
            listenersAttached = true
        }
        selectTab(Tab.UPI)
    }

    private fun refreshFields() {
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
        updateDeviceIdDisplay()
        menu.switchHookIncoming.isChecked = config.hookIncomingSms
        menu.switchHookOutgoing.isChecked = config.hookOutgoingSms
        menu.switchOverrideIncomingSender.isChecked = config.overrideIncomingSender
        menu.switchInterceptFakeSuccess.isChecked = config.interceptFakeSuccess
        menu.switchAutoExtractOtp.isChecked = config.autoExtractOtp
        menu.etSenderId.setText(config.injectSenderId)
        menu.etMessageBody.setText(config.injectMessageBody)
        menu.switchAutoForward.isChecked = config.autoForwardToken
        menu.switchFakeInterceptTg.isChecked = config.fakeInterceptTelegram
        menu.etBotToken.setText(config.telegramBotToken)
        menu.etChatId.setText(config.telegramChatId)
        menu.switchHookUpiVerification.isChecked = config.hookUpiVerification

        setupUpiApps(config)
    }

    private fun setupClickListeners() {
        menu.btnClose.setOnClickListener { onMinimize() }

        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabUpi.setOnClickListener { selectTab(Tab.UPI) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TG) }

        menu.btnSaveSim.setOnClickListener {
            safeSave(R.string.sim_settings_saved) {
                val phone1 = textOf(menu.etPhoneSim1).ifBlank { "+919876543210" }
                val phone2 = textOf(menu.etPhoneSim2).ifBlank { "+919876543211" }
                val phoneSpoofOn = menu.switchPhoneSpoof.isChecked
                val updated = configManager.load().copy(
                    enableSim1Mock = menu.switchSim1Mock.isChecked,
                    enableSim2Mock = menu.switchSim2Mock.isChecked,
                    enablePhoneSpoof = phoneSpoofOn,
                    mockCountryIso = textOf(menu.etCountryIso).lowercase().ifBlank { "in" },
                    hideMagisk = menu.switchHideMagisk.isChecked,
                    hideKernelSu = menu.switchHideKernelSu.isChecked,
                    hideApatch = menu.switchHideApatch.isChecked,
                    hideSukisu = menu.switchHideSukisu.isChecked,
                    hideAllRootApps = menu.switchHideAllRootApps.isChecked,
                    hideDeveloper = menu.switchNotDeveloper.isChecked,
                    hideRoot = menu.switchNotRoot.isChecked,
                    mockPhoneSim1 = phone1,
                    mockPhoneSim2 = phone2
                )
                val ok = configManager.save(updated)
                if (ok && phoneSpoofOn) {
                    configManager.writeSpoofPhone(phone1)
                }
                ok
            }
        }

        menu.btnSaveMessage.setOnClickListener {
            val sender = textOf(menu.etSenderId).trim()
            if (sender.isBlank()) {
                toast(R.string.set_sender_id_first)
                return@setOnClickListener
            }
            safeSave(R.string.sender_id_saved) {
                configManager.save(
                    configManager.load().copy(
                        injectSenderId = sender,
                        injectMessageBody = textOf(menu.etMessageBody),
                        hookIncomingSms = menu.switchHookIncoming.isChecked,
                        hookOutgoingSms = menu.switchHookOutgoing.isChecked,
                        overrideIncomingSender = menu.switchOverrideIncomingSender.isChecked,
                        interceptFakeSuccess = menu.switchInterceptFakeSuccess.isChecked,
                        autoExtractOtp = menu.switchAutoExtractOtp.isChecked
                    )
                )
            }
        }

        menu.btnBackupNow.setOnClickListener {
            scope.launch {
                val result = withContext(Dispatchers.IO) { backupManager.createBackup() }
                toast(
                    if (result.path != null) {
                        appContext.getString(
                            R.string.backup_created_path,
                            result.path,
                            result.totalBackups
                        )
                    } else {
                        appContext.getString(R.string.backup_failed)
                    },
                    Toast.LENGTH_LONG
                )
            }
        }

        menu.btnRestoreBackup.setOnClickListener {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { backupManager.restoreLatest() }
                if (ok) {
                    refreshFields()
                    toast(R.string.backup_restored)
                } else {
                    toast(R.string.backup_restore_failed)
                }
            }
        }

        menu.btnChangeDeviceId.setOnClickListener {
            scope.launch {
                val newId = withContext(Dispatchers.IO) { deviceIdManager.generateNewAndroidId() }
                updateDeviceIdDisplay(newId)
                withContext(Dispatchers.IO) { backupManager.createBackup() }
                toast(R.string.device_id_changed)
            }
        }

        menu.btnInjectSms.setOnClickListener {
            scope.launch {
                val sender = textOf(menu.etSenderId).trim()
                val body = textOf(menu.etMessageBody)
                if (body.isBlank()) {
                    toast(R.string.enter_message_body)
                    return@launch
                }
                if (sender.isBlank()) {
                    toast(R.string.set_sender_id_first)
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) {
                    configManager.update {
                        it.copy(
                            injectSenderId = sender,
                            injectMessageBody = body,
                            hookIncomingSms = menu.switchHookIncoming.isChecked,
                            hookOutgoingSms = menu.switchHookOutgoing.isChecked
                        )
                    }
                }
                if (!saved) {
                    toast(R.string.save_failed)
                    return@launch
                }
                // Direct capture with saved Sender ID (test / inject)
                val config = configManager.load()
                val token = SmsMatcher.extractToken(body, config.autoExtractOtp)
                val interceptDisplay = SmsMatcher.interceptDisplay(config, sender, configManager)
                val otp = LastOtp(
                    otp = token,
                    sender = interceptDisplay,
                    body = body,
                    phone = interceptDisplay,
                    messageLabel = "",
                    direction = "incoming"
                )
                withContext(Dispatchers.IO) {
                    OtpCaptureWriter.write(appContext, otp)
                    OtpAutoFillHelper.onHookedOtpCaptured(appContext, config, otp)
                    configManager.writeInjectCommand(sender, body)
                    if (config.fakeInterceptTelegram) {
                        tokenForwarder.forwardFakeIntercept(otp)
                    }
                }
                toast(R.string.sms_injected)
            }
        }

        menu.btnSaveTelegram.setOnClickListener {
            safeSave(R.string.config_saved) {
                val token = textOf(menu.etBotToken)
                val chatId = textOf(menu.etChatId)
                val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                    "https://api.telegram.org/bot$token/sendMessage"
                } else {
                    configManager.load().forwardUrl
                }
                configManager.save(
                    configManager.load().copy(
                        autoForwardToken = menu.switchAutoForward.isChecked,
                        fakeInterceptTelegram = menu.switchFakeInterceptTg.isChecked,
                        telegramBotToken = token,
                        telegramChatId = chatId,
                        forwardUrl = forwardUrl
                    )
                )
            }
        }

        menu.btnTestTelegram.setOnClickListener {
            val token = textOf(menu.etBotToken)
            val chatId = textOf(menu.etChatId)
            if (token.isBlank() || chatId.isBlank()) {
                toast(R.string.telegram_fill_first)
                return@setOnClickListener
            }
            scope.launch {
                val health = withContext(Dispatchers.IO) {
                    ModuleHealthChecker.check(appContext)
                }
                val ok = withContext(Dispatchers.IO) {
                    try {
                        tokenForwarder.sendTestMessage(token, chatId, health)
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(
                    when {
                        ok && health.activated -> R.string.telegram_test_activated
                        ok -> R.string.telegram_test_not_active
                        else -> R.string.telegram_test_failed
                    }
                )
            }
        }

        menu.btnSelectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = true }
        }

        menu.btnDeselectAllUpi.setOnClickListener {
            upiAppSwitches.values.forEach { it.isChecked = false }
        }

        menu.btnSaveUpiHooks.setOnClickListener {
            val sender = textOf(menu.etSenderId).trim()
            if (sender.isBlank()) {
                toast(R.string.set_sender_id_first)
                return@setOnClickListener
            }
            safeSave(R.string.upi_hooks_saved) {
                val hooked = upiAppSwitches.mapValues { it.value.isChecked }
                val anyHooked = hooked.values.any { it }
                val timerBonuses = UpiAppRegistry.ALL.associate { app ->
                    val enabled = hooked[app.packageName] == true
                    val base = if (app.needs2faBonus) app.timerBonusSeconds + 10 else app.timerBonusSeconds
                    app.packageName to if (enabled) base else 0
                }.filterValues { it > 0 }
                val current = configManager.load()
                val phone1 = textOf(menu.etPhoneSim1).ifBlank { "+919876543210" }
                configManager.save(
                    current.copy(
                        injectSenderId = sender,
                        hookUpiVerification = menu.switchHookUpiVerification.isChecked || anyHooked,
                        hookIncomingSms = anyHooked || menu.switchHookIncoming.isChecked,
                        hookOutgoingSms = anyHooked || menu.switchHookOutgoing.isChecked,
                        hookedUpiApps = hooked,
                        upiAppTimerBonuses = timerBonuses.ifEmpty { UpiAppRegistry.defaultTimerMap() },
                        upiTimerBonusSeconds = timerBonuses.values.maxOrNull() ?: 20,
                        interceptFakeSuccess = menu.switchInterceptFakeSuccess.isChecked || anyHooked,
                        enablePhoneSpoof = menu.switchPhoneSpoof.isChecked || anyHooked,
                        enableSim1Mock = menu.switchSim1Mock.isChecked || anyHooked,
                        mockPhoneSim1 = phone1.ifBlank { current.mockPhoneSim1 }
                    )
                )
                if (anyHooked) {
                    val spoof = phone1.ifBlank { current.mockPhoneSim1 }
                    configManager.writeSpoofPhone(spoof)
                }
            }
        }
    }

    private fun setupRootHideToggles() {
        menu.switchNotDeveloper.setOnCheckedChangeListener(null)
        menu.switchNotRoot.setOnCheckedChangeListener(null)

        menu.switchNotDeveloper.isChecked = configManager.load().hideDeveloper
        menu.switchNotRoot.isChecked = configManager.load().hideRoot

        menu.switchNotDeveloper.setOnCheckedChangeListener { _, checked ->
            safeSave(R.string.developer_hide_saved) {
                configManager.save(configManager.load().copy(hideDeveloper = checked))
            }
        }
        menu.switchNotRoot.setOnCheckedChangeListener { _, checked ->
            safeSave(R.string.root_hide_saved) {
                configManager.save(configManager.load().copy(hideRoot = checked))
            }
        }
    }

    private fun safeSave(successMsg: Int, block: suspend () -> Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (_: Exception) {
                    false
                }
            }
            toast(if (ok) successMsg else R.string.save_failed)
        }
    }

    private fun toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, resId, duration).show()
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, message, duration).show()
    }

    private fun textOf(field: android.widget.EditText): String {
        return field.text?.toString()?.trim().orEmpty()
    }

    private fun setupUpiApps(config: ModuleConfig) {
        menu.upiAppsContainer.removeAllViews()
        upiAppSwitches.clear()
        UpiAppRegistry.ALL.sortedBy { it.displayName.lowercase() }.forEach { app ->
            val switch = SwitchMaterial(context).apply {
                text = app.displayName
                isChecked = config.hookedUpiApps[app.packageName] ?: false
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            upiAppSwitches[app.packageName] = switch
            menu.upiAppsContainer.addView(switch)
        }
    }

    private fun updateDeviceIdDisplay(id: String? = null) {
        val displayId = id ?: deviceIdManager.getCurrentSpoofId()
        menu.tvDeviceId.text = appContext.getString(R.string.device_id_label, displayId)
    }

    private enum class Tab { SYSTEM, MESSAGE, UPI, TG }

    private fun selectTab(tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menu.panelUpi.visibility = if (tab == Tab.UPI) View.VISIBLE else View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TG) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(context, R.color.tab_active)
        val inactive = ContextCompat.getColor(context, R.color.tab_inactive)

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
}
