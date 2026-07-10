package com.hivirtus.zygiskmode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class OverlayMenuController(
    private val context: Context,
    private val menu: OverlayMenuBinding,
    private val onMinimize: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configManager = ConfigManager(context)
    private var listenersAttached = false
    private var suppressAutoSave = false
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var mockSimDebounce: Runnable? = null
    private var senderIdDebounce: Runnable? = null
    private var prefixDebounce: Runnable? = null
    private var telegramDebounce: Runnable? = null

    fun bind() {
        try {
            suppressAutoSave = true
            refreshFields()
            if (!listenersAttached) {
                setupClickListeners()
                setupAutoSaveListeners()
                listenersAttached = true
            }
            suppressAutoSave = false
            selectTab(Tab.SYSTEM)
            refreshStatus()
        } catch (e: Exception) {
            suppressAutoSave = false
            toast(R.string.config_saved, Toast.LENGTH_SHORT)
        }
    }

    private fun refreshFields() {
        val config = configManager.load()
        val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
        val phone = config.mockPhoneSim1.ifBlank { configManager.readSpoofPhone() }
        val digits10 = configManager.mockSimDigits10(phone)

        menu.switchMockSim.isChecked = mockOn
        menu.etMockSimNumber.setText(digits10)
        menu.etMockSimNumber.isEnabled = mockOn
        updateMockSimStatus(mockOn, digits10)

        menu.switchNotDeveloper.isChecked = config.hideDeveloper
        menu.switchNotRoot.isChecked = config.hideRoot
        menu.switchHookIncoming.isChecked = config.hookIncomingSms
        menu.switchHookOutgoing.isChecked = config.hookOutgoingSms
        menu.switchInterceptFake.isChecked = config.interceptFakeSuccess
        menu.switchPrefix.isChecked = config.prefixEnabled
        menu.etPrefix.setText(config.prefixText)
        menu.layoutPrefix.visibility = if (config.prefixEnabled) View.VISIBLE else View.GONE
        menu.switchOverrideSender.isChecked = config.overrideIncomingSender
        menu.etSenderId.setText(config.injectSenderId)
        menu.etSenderId.isEnabled = config.overrideIncomingSender
        menu.etBotToken.setText(
            TelegramCredentialStore.load(appContext).botToken.ifBlank { config.telegramBotToken }
        )
        menu.etChatId.setText(
            TelegramCredentialStore.load(appContext).chatId.ifBlank { config.telegramChatId }
        )
    }

    private fun setupClickListeners() {
        menu.btnClose.setOnClickListener {
            flushMockSimOnClose()
            flushTelegramOnClose()
            onMinimize()
        }
        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TG) }
        menu.btnShowAppInfo.setOnClickListener { openForegroundAppInfo() }
        menu.btnVerifyTelegram.setOnClickListener { verifyAndSubmitTelegram() }
    }

    private fun setupAutoSaveListeners() {
        autoToggle(menu.switchMockSim) { checked ->
            menu.etMockSimNumber.isEnabled = checked
            if (checked) {
                val digits = configManager.mockSimDigits10(textOf(menu.etMockSimNumber))
                if (digits.length == 10) {
                    persistMockSim(true, digits)
                    toast(R.string.toast_mock_sim_on)
                } else {
                    updateMockSimStatus(true, "")
                    toast(R.string.mock_sim_need_number, Toast.LENGTH_LONG)
                }
            } else {
                persistMockSim(false, textOf(menu.etMockSimNumber))
                toast(R.string.toast_mock_sim_off)
            }
        }

        menu.etMockSimNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                if (!menu.switchMockSim.isChecked) return
                mockSimDebounce?.let { debounceHandler.removeCallbacks(it) }
                val digits = configManager.mockSimDigits10(s?.toString().orEmpty())
                mockSimDebounce = Runnable {
                    if (digits.length == 10) {
                        persistMockSim(true, digits)
                    } else {
                        updateMockSimStatus(true, digits)
                    }
                }
                debounceHandler.postDelayed(mockSimDebounce!!, 450L)
            }
        })

        menu.etSenderId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                senderIdDebounce?.let { debounceHandler.removeCallbacks(it) }
                val raw = s?.toString().orEmpty().trim()
                senderIdDebounce = Runnable {
                    scope.launch(Dispatchers.IO) {
                        val ok = configManager.syncSenderId(raw)
                        if (ok) {
                            InboxSmsRewriteHelper.rewriteRecentInbox(appContext)
                            SmsStackRefresher.refreshAfterSenderIdChange()
                            withContext(Dispatchers.Main) {
                                if (raw.isNotBlank()) {
                                    toast(R.string.toast_sender_saved, raw)
                                }
                            }
                        }
                    }
                }
                debounceHandler.postDelayed(senderIdDebounce!!, 450L)
            }
        })

        menu.switchNotDeveloper.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSave) return@setOnCheckedChangeListener
            scope.launch(Dispatchers.IO) {
                val current = configManager.load()
                configManager.save(
                    current.copy(
                        hideDeveloper = checked,
                        hideRoot = if (checked) true else current.hideRoot
                    )
                )
            }
        }
        menu.switchNotRoot.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSave) return@setOnCheckedChangeListener
            scope.launch(Dispatchers.IO) {
                val current = configManager.load()
                configManager.save(
                    current.copy(
                        hideRoot = checked,
                        hideMagisk = checked,
                        hideKernelSu = checked,
                        hideApatch = checked,
                        hideSukisu = checked,
                        hideAllRootApps = checked
                    )
                )
            }
        }
        autoToggle(menu.switchOverrideSender) { checked ->
            menu.etSenderId.isEnabled = checked
            configManager.syncSenderOverride(checked, textOf(menu.etSenderId))
            if (checked) {
                val id = textOf(menu.etSenderId).ifBlank { "—" }
                toast(R.string.toast_sender_on, id)
                InboxSmsRewriteHelper.rewriteRecentInbox(appContext)
            } else {
                toast(R.string.toast_sender_off)
            }
            SmsStackRefresher.refreshAfterSenderIdChange()
        }

        autoToggle(menu.switchHookIncoming) { checked ->
            savePartial {
                it.copy(
                    hookIncomingSms = checked,
                    autoHookForeground = true
                )
            }
            toast(if (checked) R.string.toast_incoming_on else R.string.toast_incoming_off)
            HookStatusBarManager(appContext).refresh()
        }
        autoToggle(menu.switchHookOutgoing) { checked ->
            savePartial {
                it.copy(
                    hookOutgoingSms = checked,
                    autoHookForeground = true
                )
            }
            toast(if (checked) R.string.toast_outgoing_on else R.string.toast_outgoing_off)
            OutgoingSmsGuard.refresh(appContext)
            HookStatusBarManager(appContext).refresh()
            refreshStatus()
        }

        autoToggle(menu.switchInterceptFake) { checked ->
            savePartial {
                it.copy(
                    interceptFakeSuccess = checked,
                    autoHookForeground = true
                )
            }
            toast(if (checked) R.string.toast_intercept_on else R.string.toast_intercept_off)
            OutgoingSmsGuard.refresh(appContext)
            refreshStatus()
        }

        autoToggle(menu.switchPrefix) { checked ->
            menu.layoutPrefix.visibility = if (checked) View.VISIBLE else View.GONE
            savePartial {
                it.copy(
                    prefixEnabled = checked,
                    prefixText = if (checked) textOf(menu.etPrefix) else it.prefixText
                )
            }
            toast(if (checked) R.string.toast_prefix_on else R.string.toast_prefix_off)
        }

        menu.etPrefix.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                if (!menu.switchPrefix.isChecked) return
                prefixDebounce?.let { debounceHandler.removeCallbacks(it) }
                prefixDebounce = Runnable {
                    savePartial { it.copy(prefixText = s?.toString().orEmpty().trim()) }
                }
                debounceHandler.postDelayed(prefixDebounce!!, 450L)
            }
        })

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                telegramDebounce?.let { debounceHandler.removeCallbacks(it) }
                telegramDebounce = Runnable {
                    val token = textOf(menu.etBotToken)
                    val chatId = textOf(menu.etChatId)
                    if (token.isNotBlank() && chatId.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            configManager.syncTelegramCredentials(token, chatId)
                        }
                    } else {
                        persistTelegramFields(autoForward = false)
                    }
                }
                debounceHandler.postDelayed(telegramDebounce!!, 600L)
            }
        }
        menu.etBotToken.addTextChangedListener(textWatcher)
        menu.etChatId.addTextChangedListener(textWatcher)
    }

    private fun autoToggle(
        switch: com.google.android.material.switchmaterial.SwitchMaterial,
        onChanged: (Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSave) return@setOnCheckedChangeListener
            scope.launch(Dispatchers.IO) {
                try {
                    onChanged(checked)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun flushTelegramOnClose() {
        val token = textOf(menu.etBotToken)
        val chatId = textOf(menu.etChatId)
        if (token.isBlank() || chatId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            configManager.syncTelegramCredentials(token, chatId)
        }
    }

    private fun flushMockSimOnClose() {
        if (!menu.switchMockSim.isChecked) return
        val digits = configManager.mockSimDigits10(textOf(menu.etMockSimNumber))
        if (digits.length != 10) return
        runBlocking(Dispatchers.IO) {
            configManager.syncMockSim(true, digits)
            MockSimPipeline.activate(appContext)
        }
    }

    private fun persistMockSim(enabled: Boolean, phoneRaw: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                configManager.syncMockSim(enabled, phoneRaw)
            }
            val digits = configManager.mockSimDigits10(phoneRaw)
            updateMockSimStatus(enabled, digits)
            if (ok) {
                MockSimPipeline.activate(appContext)
                HookStatusBarManager(appContext).refresh()
                OutgoingSmsGuard.refresh(appContext)
                if (enabled && digits.length == 10) {
                    toast(R.string.mock_sim_saved, Toast.LENGTH_LONG)
                }
            } else if (enabled) {
                toast(R.string.mock_sim_need_number, Toast.LENGTH_LONG)
            }
            refreshStatus()
        }
    }

    private fun updateMockSimStatus(enabled: Boolean, digits10: String) {
        menu.tvMockSimStatus.text = when {
            enabled && digits10.length == 10 ->
                appContext.getString(R.string.mock_sim_status_on, digits10)
            enabled ->
                appContext.getString(R.string.mock_sim_need_number)
            else ->
                appContext.getString(R.string.mock_sim_status_off)
        }
    }

    private fun savePartial(transform: (ModuleConfig) -> ModuleConfig): Boolean {
        return configManager.save(transform(configManager.load()))
    }

    private fun persistTelegramFields(autoForward: Boolean) {
        scope.launch(Dispatchers.IO) {
            val token = textOf(menu.etBotToken)
            val chatId = textOf(menu.etChatId)
            if (token.isNotBlank() && chatId.isNotBlank()) {
                configManager.syncTelegramCredentials(token, chatId)
                return@launch
            }
            val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                "https://api.telegram.org/bot$token/sendMessage"
            } else {
                configManager.load().forwardUrl
            }
            configManager.save(
                configManager.load().copy(
                    telegramBotToken = token,
                    telegramChatId = chatId,
                    forwardUrl = forwardUrl,
                    autoForwardToken = autoForward || (token.isNotBlank() && chatId.isNotBlank())
                )
            )
        }
    }

    private fun verifyAndSubmitTelegram() {
        val token = textOf(menu.etBotToken)
        val chatId = textOf(menu.etChatId)
        if (token.isBlank() || chatId.isBlank()) {
            toast(R.string.telegram_fields_required, Toast.LENGTH_LONG)
            selectTab(Tab.TG)
            return
        }
        scope.launch {
            persistTelegramFields(autoForward = true)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    TokenForwarder(configManager, appContext).sendForwardingTest(token, chatId)
                }.getOrDefault(false)
            }
            toast(
                if (ok) R.string.telegram_verified else R.string.telegram_verify_failed,
                Toast.LENGTH_LONG
            )
            refreshStatus()
        }
    }

    private fun openForegroundAppInfo() {
        val pkg = ForegroundAppHelper.foregroundPackage(appContext)
            ?: appContext.packageName
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: Exception) {
            toast(R.string.app_info_failed, Toast.LENGTH_SHORT)
        }
    }

    private fun toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, resId, duration).show()
    }

    private fun toast(resId: Int, arg: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, appContext.getString(resId, arg), duration).show()
    }

    private fun textOf(field: android.widget.EditText): String =
        field.text?.toString()?.trim().orEmpty()

    private enum class Tab { SYSTEM, MESSAGE, TG }

    private fun refreshStatus() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { ModuleHealthChecker.check(appContext) }
            val config = withContext(Dispatchers.IO) { configManager.load() }
            val creds = withContext(Dispatchers.IO) { TelegramCredentialStore.load(appContext) }
            val smsDenied = File("/data/local/tmp/hivirtus_phone_sms_denied.flag").exists()
            val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
            val mockReady = mockOn && configManager.mockSimDigits10(
                config.mockPhoneSim1.ifBlank { configManager.readSpoofPhone() }
            ).length == 10
            val telegramReady = (creds.botToken.isNotBlank() && creds.chatId.isNotBlank()) ||
                (config.telegramBotToken.isNotBlank() && config.telegramChatId.isNotBlank())
            val outgoingOn = config.hookOutgoingSms

            val (textRes, dotRes, colorRes) = when {
                !health.moduleInstalled || !health.zygiskLoaded ->
                    Triple(R.string.status_offline, R.drawable.status_dot_red, R.color.status_error)
                mockReady && outgoingOn && telegramReady && smsDenied ->
                    Triple(R.string.status_active, R.drawable.status_dot_green, R.color.status_connected)
                mockReady || outgoingOn ->
                    Triple(R.string.status_partial, R.drawable.status_dot_yellow, R.color.status_connecting)
                else ->
                    Triple(R.string.status_partial, R.drawable.status_dot_yellow, R.color.status_connecting)
            }

            menu.statusDot.setBackgroundResource(dotRes)
            menu.tvStatusValue.setText(textRes)
            menu.tvStatusValue.setTextColor(ContextCompat.getColor(appContext, colorRes))
        }
    }

    private fun selectTab(tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TG) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(context, R.color.tab_active)
        val inactive = ContextCompat.getColor(context, R.color.tab_inactive)
        listOf(
            menu.tabSystem to Tab.SYSTEM,
            menu.tabMessage to Tab.MESSAGE,
            menu.tabTelegram to Tab.TG
        ).forEach { (view, t) ->
            view.isSelected = tab == t
            view.setTextColor(if (tab == t) active else inactive)
        }
    }
}
