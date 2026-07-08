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
    private val configManager = ConfigManager(context)
    private val tokenForwarder = TokenForwarder(configManager)
    private val backupManager = BackupManager(context)
    private val deviceIdManager = DeviceIdManager(context)
    private val upiAppSwitches = mutableMapOf<String, SwitchMaterial>()

    fun bind() {
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
        setupRootHideToggles()

        menu.btnClose.setOnClickListener { onMinimize() }

        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabUpi.setOnClickListener { selectTab(Tab.UPI) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TG) }

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
            Toast.makeText(context, R.string.sim_settings_saved, Toast.LENGTH_SHORT).show()
        }

        menu.btnBackupNow.setOnClickListener {
            val path = backupManager.createBackup()
            Toast.makeText(
                context,
                if (path != null) context.getString(R.string.backup_created_path, path)
                else context.getString(R.string.backup_failed),
                Toast.LENGTH_LONG
            ).show()
        }

        menu.btnRestoreBackup.setOnClickListener {
            val ok = backupManager.restoreLatest()
            if (ok) {
                bind()
                Toast.makeText(context, R.string.backup_restored, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.backup_restore_failed, Toast.LENGTH_SHORT).show()
            }
        }

        menu.btnChangeDeviceId.setOnClickListener {
            val newId = deviceIdManager.generateNewAndroidId()
            updateDeviceIdDisplay(newId)
            backupManager.createBackup()
            Toast.makeText(context, R.string.device_id_changed, Toast.LENGTH_SHORT).show()
        }

        menu.btnInjectSms.setOnClickListener {
            val sender = menu.etSenderId.text?.toString()?.ifBlank { "AD-TEST-S" } ?: "AD-TEST-S"
            val body = menu.etMessageBody.text?.toString().orEmpty()
            if (body.isBlank()) {
                Toast.makeText(context, R.string.enter_message_body, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, R.string.sms_injected, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }

        menu.btnTestTelegram.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = tokenForwarder.sendTestMessage()
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
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
            Toast.makeText(context, R.string.upi_hooks_saved, Toast.LENGTH_SHORT).show()
        }

        selectTab(Tab.UPI)
    }

    private fun setupRootHideToggles() {
        menu.switchNotDeveloper.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(hideDeveloper = checked) }
            Toast.makeText(context, R.string.developer_hide_saved, Toast.LENGTH_SHORT).show()
        }
        menu.switchNotRoot.setOnCheckedChangeListener { _, checked ->
            configManager.update { it.copy(hideRoot = checked) }
            Toast.makeText(context, R.string.root_hide_saved, Toast.LENGTH_SHORT).show()
        }
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
        menu.tvDeviceId.text = context.getString(R.string.device_id_label, displayId)
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
