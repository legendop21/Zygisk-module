package com.hivirtus.zygiskmode

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
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
    private var listenersAttached = false
    private var suppressAutoSave = false

    fun bind() {
        suppressAutoSave = true
        refreshFields()
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
        menu.switchAutoForward.isChecked = config.autoForwardToken
        menu.switchFakeInterceptTg.isChecked = false
        menu.etBotToken.setText(config.telegramBotToken)
        menu.etChatId.setText(config.telegramChatId)
    }

    private fun setupClickListeners() {
        menu.btnClose.setOnClickListener { onMinimize() }
        menu.btnStartHook.setOnClickListener { startZygiskHooks() }
        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TG) }
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
        autoToggle(menu.switchAutoForward) { checked ->
            savePartial { it.copy(autoForwardToken = checked) }
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
            val forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                "https://api.telegram.org/bot$token/sendMessage"
            } else {
                configManager.load().forwardUrl
            }
            val saved = configManager.save(
                configManager.load().copy(
                    mockPhoneSim1 = phone,
                    injectSenderId = sender,
                    telegramBotToken = token,
                    telegramChatId = chatId,
                    forwardUrl = forwardUrl,
                    enablePhoneSpoof = menu.switchPhoneSpoof.isChecked,
                    enableSim1Mock = menu.switchSim1Mock.isChecked,
                    hookIncomingSms = menu.switchHookIncoming.isChecked,
                    hookOutgoingSms = menu.switchHookOutgoing.isChecked,
                    overrideIncomingSender = menu.switchOverrideIncomingSender.isChecked,
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
        val name = ActiveHookManager.readActiveDisplayName(appContext)
        val pkg = ActiveHookManager.readActivePackage()
        menu.tvActiveHook.text = if (pkg.isNullOrBlank()) {
            appContext.getString(R.string.active_hook_none)
        } else {
            appContext.getString(R.string.active_hook_app, name)
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

            persistAllFields()

            val hookResult = withContext(Dispatchers.IO) {
                ActiveHookManager.hookForegroundApp(appContext, configManager)
            }

            if (!hookResult.success) {
                toast(hookResult.message, Toast.LENGTH_LONG)
                menu.tvHookStatus.text = hookResult.message
                return@launch
            }

            withContext(Dispatchers.IO) {
                configManager.save(
                    configManager.load().copy(
                        injectSenderId = sender,
                        mockPhoneSim1 = phone,
                        enablePhoneSpoof = true,
                        enableSim1Mock = true,
                        hookIncomingSms = true,
                        hookOutgoingSms = true,
                        overrideIncomingSender = true,
                        interceptFakeSuccess = true,
                        autoExtractOtp = true,
                        hideRoot = menu.switchNotRoot.isChecked,
                        hideDeveloper = menu.switchNotDeveloper.isChecked,
                        autoForwardToken = menu.switchAutoForward.isChecked,
                        fakeInterceptTelegram = false
                    )
                )
                configManager.writeSpoofPhone(phone)
            }

            menu.switchSim1Mock.isChecked = true
            menu.switchPhoneSpoof.isChecked = true
            menu.switchHookIncoming.isChecked = true
            menu.switchHookOutgoing.isChecked = true
            menu.switchOverrideIncomingSender.isChecked = true

            withContext(Dispatchers.IO) { OutgoingSmsGuard.refresh(appContext) }
            HookStatusBarManager(appContext).refresh()

            menu.tvHookStatus.text = appContext.getString(R.string.hook_status_ready)
            updateActiveHookLabel()
            toast(
                appContext.getString(R.string.start_hook_app_success, hookResult.displayName),
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

    private fun toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, resId, duration).show()
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(appContext, message, duration).show()
    }

    private fun textOf(field: android.widget.EditText): String =
        field.text?.toString()?.trim().orEmpty()

    private enum class Tab { SYSTEM, MESSAGE, TG }

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
