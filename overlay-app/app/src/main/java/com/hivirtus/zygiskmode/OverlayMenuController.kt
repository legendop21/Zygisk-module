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
import kotlinx.coroutines.launch

class OverlayMenuController(
    private val context: Context,
    private val menu: OverlayMenuBinding,
    private val onMinimize: () -> Unit
) {
    private val appContext = context.applicationContext
    private val configManager = ConfigManager(context)
    private val tokenForwarder = TokenForwarder(configManager)
    private val backupManager = BackupManager(context)
    private val deviceIdManager = DeviceIdManager(context)
    private val upiAppSwitches = mutableMapOf<String, SwitchMaterial>()

    fun bind() {
        refreshFields()
        setupRootHideToggles()
        setupClickListeners()
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
        menu.etSenderId.setText(config.injectSenderId)
        menu.etMessageBody.setText(config.injectMessageBody)
        menu.switchAutoForward.isChecked = config.autoForwardToken
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
            }
        }

        menu.btnBackupNow.setOnClickListener {
            safeAction {
                val path = backupManager.createBackup()
                toast(
                    if (path != null) appContext.getString(R.string.backup_created_path, path)
                    else appContext.getString(R.string.backup_failed),
                    Toast.LENGTH_LONG
                )
            }
        }

        menu.btnRestoreBackup.setOnClickListener {
            safeAction {
                val ok = backupManager.restoreLatest()
                if (ok) {
                    refreshFields()
                    toast(R.string.backup_restored)
                } else {
                    toast(R.string.backup_restore_failed)
                }
            }
        }

        menu.btnChangeDeviceId.setOnClickListener {
            safeAction {
                val newId = deviceIdManager.generateNewAndroidId()
                updateDeviceIdDisplay(newId)
                backupManager.createBackup()
                toast(R.string.device_id_changed)
            }
        }

        menu.btnInjectSms.setOnClickListener {
            safeAction {
                val sender = menu.etSenderId.text?.toString()?.trim()?.ifBlank { "AD-TEST-S" } ?: "AD-TEST-S"
                val body = menu.etMessageBody.text?.toString()?.trim().orEmpty()
                if (body.isBlank()) {
                    toast(R.string.enter_message_body)
                    return@safeAction
                }
                val saved = configManager.update {
                    it.copy(
                        injectSenderId = sender,
                        injectMessageBody = body,
                        hookIncomingSms = menu.switchHookIncoming.isChecked,
                        hookOutgoingSms = menu.switchHookOutgoing.isChecked
                    )
                }
                if (!saved) {
                    toast(R.string.save_failed)
                    return@safeAction
                }
                if (!configManager.writeInjectCommand(sender, body)) {
                    toast(R.string.inject_queued_local)
                } else {
                    toast(R.string.sms_injected)
                }
            }
        }

        menu.btnSaveTelegram.setOnClickListener {
            safeSave(R.string.config_saved) {
                val token = menu.etBotToken.text?.toString()?.trim().orEmpty()
                val chatId = menu.etChatId.text?.toString()?.trim().orEmpty()
                val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                    "https://api.telegram.org/bot$token/sendMessage"
                } else {
                    configManager.load().forwardUrl
                }
                configManager.save(
                    configManager.load().copy(
                        autoForwardToken = menu.switchAutoForward.isChecked,
                        telegramBotToken = token,
                        telegramChatId = chatId,
                        forwardUrl = forwardUrl
                    )
                )
            }
        }

        menu.btnTestTelegram.setOnClickListener {
            safeAction {
                val token = menu.etBotToken.text?.toString()?.trim().orEmpty()
                val chatId = menu.etChatId.text?.toString()?.trim().orEmpty()
                if (token.isBlank() || chatId.isBlank()) {
                    toast(R.string.telegram_fill_first)
                    return@safeAction
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = try {
                        tokenForwarder.sendTestMessage()
                    } catch (_: Exception) {
                        false
                    }
                    launch(Dispatchers.Main) {
                        toast(if (ok) R.string.telegram_test_sent else R.string.telegram_test_failed)
                    }
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
            safeSave(R.string.upi_hooks_saved) {
                val hooked = upiAppSwitches.mapValues { it.value.isChecked }
                configManager.save(
                    configManager.load().copy(
                        hookUpiVerification = menu.switchHookUpiVerification.isChecked,
                        hookedUpiApps = hooked
                    )
                )
            }
        }
    }

    private fun setupRootHideToggles() {
        menu.switchNotDeveloper.setOnCheckedChangeListener(null)
        menu.switchNotRoot.setOnCheckedChangeListener(null)

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

    private fun safeSave(successMsg: Int, block: () -> Boolean) {
        safeAction {
            val ok = try {
                block()
            } catch (_: Exception) {
                false
            }
            if (ok) toast(successMsg) else toast(R.string.save_failed)
        }
    }

    private fun safeAction(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            toast(appContext.getString(R.string.action_failed, e.message ?: "error"), Toast.LENGTH_LONG)
        }
    }

    private fun toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, resId, duration).show()
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, message, duration).show()
    }

    private fun setupUpiApps(config: ModuleConfig) {
        menu.upiAppsContainer.removeAllViews()
        upiAppSwitches.clear()
        UpiAppRegistry.ALL.sortedBy { it.displayName.lowercase() }.forEach { app ->
            val switch = SwitchMaterial(context).apply {
                text = app.displayName
                isChecked = config.hookedUpiApps[app.packageName] ?: true
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
