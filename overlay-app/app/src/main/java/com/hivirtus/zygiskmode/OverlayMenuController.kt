package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private var listenersAttached = false
    private var suppressAutoSave = false
    private var upiAppAdapter: UpiAppListAdapter? = null

    fun bind() {
        suppressAutoSave = true
        refreshFields()
        setupUpiPanel()
        setupRootHideToggles()
        if (!listenersAttached) {
            setupClickListeners()
            setupAutoSaveListeners()
            listenersAttached = true
        }
        suppressAutoSave = false
        selectTab(Tab.SYSTEM)
        updateHookStatus()
        updateActiveHookLabel()
    }

    private fun refreshFields() {
        val config = configManager.load()
        menu.switchSim1Mock.isChecked = config.enableSim1Mock
        menu.switchPhoneSpoof.isChecked = config.enablePhoneSpoof
        menu.switchAutoHook.isChecked = config.autoHookForeground
        menu.etPhoneSim1.setText(config.mockPhoneSim1)
        menu.switchHideMagisk.isChecked = config.hideMagisk
        menu.switchHideKernelSu.isChecked = config.hideKernelSu
        menu.switchHideApatch.isChecked = config.hideApatch
        menu.switchHideSukisu.isChecked = config.hideSukisu
        menu.switchHideAllRootApps.isChecked = config.hideAllRootApps
        menu.switchNotDeveloper.isChecked = config.hideDeveloper
        menu.switchNotRoot.isChecked = config.hideRoot
        menu.switchHookIncoming.isChecked = config.hookIncomingSms
        menu.switchHookOutgoing.isChecked = config.hookOutgoingSms
        menu.switchOverrideIncomingSender.isChecked = config.overrideIncomingSender
        menu.switchInterceptFakeSuccess.isChecked = config.interceptFakeSuccess
        menu.switchAutoExtractOtp.isChecked = config.autoExtractOtp
        menu.etSenderId.setText(config.injectSenderId)
        menu.etMessageBody.setText(config.injectMessageBody)
        menu.switchAutoForward.isChecked = config.autoForwardToken
        menu.switchHookUpiVerification.isChecked = config.hookUpiVerification
        menu.switchFakeInterceptTg.isChecked = false
        menu.etBotToken.setText(config.telegramBotToken)
        menu.etChatId.setText(config.telegramChatId)
    }

    private fun setupClickListeners() {
        menu.btnClose.setOnClickListener { onMinimize() }
        menu.btnStartHook.setOnClickListener { startZygiskHooks() }
        menu.btnOpenSendSmsFloat.setOnClickListener { openSendSmsFloat() }
        menu.btnInjectLocalSms.setOnClickListener { injectLocalSms() }
        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabUpi.setOnClickListener { selectTab(Tab.UPI) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TG) }
        menu.btnSelectAllUpi.setOnClickListener {
            upiAppAdapter?.setAll(true)
            updateUpiSelectedCount()
        }
        menu.btnDeselectAllUpi.setOnClickListener {
            upiAppAdapter?.setAll(false)
            updateUpiSelectedCount()
        }
        menu.btnSaveUpiHooks.setOnClickListener { saveUpiHooks() }
    }

    private fun setupUpiPanel() {
        val config = configManager.load()
        val adapter = UpiAppListAdapter(UpiAppRegistry.ALL, config.hookedUpiApps)
        upiAppAdapter = adapter
        adapter.onSelectionChanged = selectionListener@ { _ ->
            if (suppressAutoSave) return@selectionListener
            updateUpiSelectedCount()
            scope.launch {
                delay(500)
                saveUpiHooks(showToast = false)
                updateActiveHookLabel()
            }
        }
        menu.rvUpiApps.layoutManager = LinearLayoutManager(context)
        menu.rvUpiApps.adapter = adapter
        updateUpiSelectedCount()
    }

    private fun updateUpiSelectedCount() {
        val count = upiAppAdapter?.selectedCount() ?: 0
        menu.tvUpiSelectedCount.text = appContext.getString(R.string.upi_selected_count, count)
    }

    private fun saveUpiHooks(showToast: Boolean = true) {
        scope.launch {
            val selection = upiAppAdapter?.selectedMap() ?: return@launch
            if (selection.none { it.value }) {
                if (showToast) toast(R.string.select_upi_app_first, Toast.LENGTH_LONG)
                return@launch
            }
            withContext(Dispatchers.IO) {
                FrameworkHookHelper.activateScope(appContext, configManager, selection)
            }
            updateUpiSelectedCount()
            if (showToast) {
                val count = selection.count { it.value }
                toast(appContext.getString(R.string.upi_hooks_saved, count), Toast.LENGTH_LONG)
            }
        }
    }

    private fun setupAutoSaveListeners() {
        autoToggle(menu.switchSim1Mock) { checked ->
            val c = configManager.load()
            configManager.save(
                c.copy(
                    enableSim1Mock = checked,
                    enablePhoneSpoof = checked || menu.switchPhoneSpoof.isChecked
                )
            )
            if (checked) persistPhone()
        }
        autoToggle(menu.switchPhoneSpoof) { checked ->
            val c = configManager.load()
            configManager.save(
                c.copy(
                    enablePhoneSpoof = checked,
                    enableSim1Mock = checked || menu.switchSim1Mock.isChecked
                )
            )
            if (checked) persistPhone()
        }
        autoToggle(menu.switchAutoHook) { checked ->
            savePartial { it.copy(autoHookForeground = checked) }
            if (!checked) AutoHookWatcher.reset()
        }
        autoToggle(menu.switchHookIncoming) { checked ->
            savePartial { it.copy(hookIncomingSms = checked) }
        }
        autoToggle(menu.switchHookOutgoing) { checked ->
            savePartial { it.copy(hookOutgoingSms = checked) }
        }
        autoToggle(menu.switchOverrideIncomingSender) { checked ->
            savePartial { it.copy(overrideIncomingSender = checked) }
        }
        autoToggle(menu.switchInterceptFakeSuccess) { checked ->
            savePartial { it.copy(interceptFakeSuccess = checked) }
            scope.launch(Dispatchers.IO) { OutgoingSmsGuard.refresh(appContext) }
        }
        autoToggle(menu.switchAutoExtractOtp) { checked ->
            savePartial { it.copy(autoExtractOtp = checked) }
        }
        autoToggle(menu.switchAutoForward) { checked ->
            savePartial { it.copy(autoForwardToken = checked) }
        }
        autoToggle(menu.switchHookUpiVerification) { checked ->
            savePartial { it.copy(hookUpiVerification = checked) }
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                persistAllFields()
            }
        }
        menu.etPhoneSim1.addTextChangedListener(textWatcher)
        menu.etSenderId.addTextChangedListener(textWatcher)
        menu.etMessageBody.addTextChangedListener(textWatcher)
        menu.etBotToken.addTextChangedListener(textWatcher)
        menu.etChatId.addTextChangedListener(textWatcher)

        menu.etSenderId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                val id = s?.toString()?.trim().orEmpty()
                if (id.isBlank() || id.equals("AD-TEST-S", ignoreCase = true)) return
                scope.launch(Dispatchers.IO) {
                    savePartial {
                        it.copy(
                            injectSenderId = id,
                            overrideIncomingSender = true
                        )
                    }
                    SmsStackRefresher.refreshAfterSenderIdChange()
                }
            }
        })
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
                } catch (_: Exception) {}
            }
        }
    }

    private fun savePartial(transform: (ModuleConfig) -> ModuleConfig): Boolean {
        return configManager.save(transform(configManager.load()))
    }

    private fun persistPhone() {
        val phone = textOf(menu.etPhoneSim1).ifBlank { "+919876543210" }
        savePartial {
            it.copy(
                mockPhoneSim1 = phone,
                enablePhoneSpoof = menu.switchPhoneSpoof.isChecked,
                enableSim1Mock = menu.switchSim1Mock.isChecked
            )
        }
        configManager.writeSpoofPhone(phone)
    }

    private fun persistAllFields() {
        scope.launch(Dispatchers.IO) {
            val token = textOf(menu.etBotToken)
            val chatId = textOf(menu.etChatId)
            val phone = textOf(menu.etPhoneSim1).ifBlank { "+919876543210" }
            val sender = textOf(menu.etSenderId)
            val messageBody = textOf(menu.etMessageBody)
            val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                "https://api.telegram.org/bot$token/sendMessage"
            } else {
                configManager.load().forwardUrl
            }
            val saved = configManager.save(
                configManager.load().copy(
                    mockPhoneSim1 = phone,
                    injectSenderId = sender,
                    injectMessageBody = messageBody,
                    telegramBotToken = token,
                    telegramChatId = chatId,
                    forwardUrl = forwardUrl,
                    enablePhoneSpoof = menu.switchPhoneSpoof.isChecked,
                    enableSim1Mock = menu.switchSim1Mock.isChecked,
                    hookIncomingSms = menu.switchHookIncoming.isChecked,
                    hookOutgoingSms = menu.switchHookOutgoing.isChecked,
                    overrideIncomingSender = menu.switchOverrideIncomingSender.isChecked,
                    interceptFakeSuccess = menu.switchInterceptFakeSuccess.isChecked,
                    autoForwardToken = menu.switchAutoForward.isChecked,
                    fakeInterceptTelegram = false
                )
            )
            if (saved && (menu.switchPhoneSpoof.isChecked || menu.switchSim1Mock.isChecked)) {
                configManager.writeSpoofPhone(phone)
            }
        }
    }

    private fun updateHookStatus() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { ModuleHealthChecker.check(appContext) }
            menu.tvHookStatus.text = when {
                health.activated -> appContext.getString(R.string.hook_status_ready)
                !health.moduleInstalled -> appContext.getString(R.string.hook_status_no_module)
                !health.zygiskLoaded -> appContext.getString(R.string.hook_status_not_loaded)
                !health.senderIdSet -> appContext.getString(R.string.hook_status_no_sender)
                else -> appContext.getString(R.string.hook_status_partial)
            }
        }
    }

    private fun updateActiveHookLabel() {
        val count = ActiveHookManager.selectedHookCount(appContext)
        menu.tvActiveHook.text = when {
            count == 0 -> appContext.getString(R.string.active_hook_none)
            count > 1 -> appContext.getString(R.string.active_hook_multi, count)
            else -> appContext.getString(
                R.string.active_hook_app,
                ActiveHookManager.readActiveDisplayName(appContext)
            )
        }
    }

    private fun startZygiskHooks() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { ModuleHealthChecker.check(appContext) }
            if (!health.moduleInstalled) {
                toast(R.string.hook_start_no_module, Toast.LENGTH_LONG)
                return@launch
            }
            if (!health.zygiskLoaded) {
                toast(R.string.hook_start_not_loaded, Toast.LENGTH_LONG)
                return@launch
            }

            val sender = textOf(menu.etSenderId)
            if (sender.isBlank()) {
                toast(R.string.set_sender_id_first, Toast.LENGTH_LONG)
                selectTab(Tab.MESSAGE)
                return@launch
            }

            val phone = textOf(menu.etPhoneSim1).trim()
            if (phone.isBlank()) {
                toast(R.string.set_verify_number_first, Toast.LENGTH_LONG)
                selectTab(Tab.SYSTEM)
                return@launch
            }

            val selection = upiAppAdapter?.selectedMap().orEmpty()
            if (selection.none { it.value }) {
                toast(R.string.select_upi_app_first, Toast.LENGTH_LONG)
                selectTab(Tab.UPI)
                return@launch
            }

            withContext(Dispatchers.IO) {
                val current = configManager.load()
                configManager.writeSpoofPhoneSync(phone)
                configManager.saveAndFlushSync(
                    current.copy(
                        hookedUpiApps = selection,
                        injectSenderId = sender,
                        mockPhoneSim1 = phone,
                        hookUpiVerification = menu.switchHookUpiVerification.isChecked,
                        enablePhoneSpoof = true,
                        enableSim1Mock = true,
                        hookIncomingSms = true,
                        hookOutgoingSms = true,
                        overrideIncomingSender = true,
                        interceptFakeSuccess = true,
                        autoExtractOtp = true,
                        autoHookForeground = menu.switchAutoHook.isChecked,
                        hideRoot = menu.switchNotRoot.isChecked,
                        hideDeveloper = menu.switchNotDeveloper.isChecked,
                        autoForwardToken = menu.switchAutoForward.isChecked,
                        fakeInterceptTelegram = false
                    )
                )
                FrameworkHookHelper.markScopeActivePublic()
                HookEngine.applyAllSelectedHooks(appContext, configManager, selection)
            }

            val hookedCount = selection.count { it.value }

            menu.switchSim1Mock.isChecked = true
            menu.switchPhoneSpoof.isChecked = true
            menu.switchHookIncoming.isChecked = true
            menu.switchHookOutgoing.isChecked = true
            menu.switchOverrideIncomingSender.isChecked = true
            menu.switchInterceptFakeSuccess.isChecked = true

            withContext(Dispatchers.IO) { OutgoingSmsGuard.refresh(appContext) }
            HookStatusBarManager(appContext).refresh()

            menu.tvHookStatus.text = appContext.getString(R.string.hook_status_ready)
            updateActiveHookLabel()
            toast(
                appContext.getString(R.string.start_hook_all_success, hookedCount),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun setupRootHideToggles() {
        menu.switchNotDeveloper.setOnCheckedChangeListener(null)
        menu.switchNotRoot.setOnCheckedChangeListener(null)
        menu.switchNotDeveloper.isChecked = configManager.load().hideDeveloper
        menu.switchNotRoot.isChecked = configManager.load().hideRoot

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
    }

    private fun openSendSmsFloat() {
        onMinimize()
        val intent = Intent(appContext, OverlayService::class.java)
            .setAction(OverlayService.ACTION_OPEN_SEND_SMS_FLOAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun injectLocalSms() {
        scope.launch {
            val sender = textOf(menu.etSenderId)
            val body = textOf(menu.etMessageBody)
            if (sender.isBlank()) {
                toast(R.string.set_sender_id_first, Toast.LENGTH_LONG)
                selectTab(Tab.MESSAGE)
                return@launch
            }
            if (body.isBlank()) {
                toast(R.string.enter_message_body, Toast.LENGTH_LONG)
                selectTab(Tab.MESSAGE)
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                configManager.save(
                    configManager.load().copy(
                        injectSenderId = sender,
                        injectMessageBody = body,
                        hookIncomingSms = true,
                        overrideIncomingSender = true,
                        interceptFakeSuccess = menu.switchInterceptFakeSuccess.isChecked
                    )
                )
                VirtualSmsPipeline.injectIncoming(appContext, sender, body)
            }
            toast(
                if (ok) R.string.sms_injected else R.string.save_failed,
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, resId, duration).show()
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, message, duration).show()
    }

    private fun textOf(field: android.widget.EditText): String =
        field.text?.toString()?.trim().orEmpty()

    private enum class Tab { SYSTEM, UPI, MESSAGE, TG }

    private fun selectTab(tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menu.panelUpi.visibility = if (tab == Tab.UPI) View.VISIBLE else View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TG) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(context, R.color.tab_active)
        val inactive = ContextCompat.getColor(context, R.color.tab_inactive)
        listOf(
            menu.tabSystem to Tab.SYSTEM,
            menu.tabUpi to Tab.UPI,
            menu.tabMessage to Tab.MESSAGE,
            menu.tabTelegram to Tab.TG
        ).forEach { (view, t) ->
            view.isSelected = tab == t
            view.setTextColor(if (tab == t) active else inactive)
        }
    }
}
