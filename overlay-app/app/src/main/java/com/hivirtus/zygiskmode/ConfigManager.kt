package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ModuleConfig(
    val hideRoot: Boolean = true,
    val hideDeveloper: Boolean = true,
    val enableSim1Mock: Boolean = false,
    val enableSim2Mock: Boolean = false,
    val mockCountryIso: String = "in",
    val hookIncomingSms: Boolean = true,
    val hookOutgoingSms: Boolean = true,
    val autoExtractOtp: Boolean = true,
    val autoForwardToken: Boolean = true,
    val forwardUrl: String = "",
    val forwardMethod: String = "POST",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val injectSenderId: String = "AD-TEST-S",
    val injectMessageBody: String = ""
)

data class LastOtp(
    val otp: String,
    val sender: String,
    val direction: String = "incoming"
)

class ConfigManager(private val context: Context) {

    private val runtimeConfig = File(RUNTIME_CONFIG)
    private val moduleConfig = File(MODULE_CONFIG)
    private val injectCommand = File(INJECT_COMMAND)
    private val lastOtpFile = File(LAST_OTP_FILE)

    fun load(): ModuleConfig {
        val file = when {
            runtimeConfig.exists() -> runtimeConfig
            moduleConfig.exists() -> moduleConfig
            else -> return ModuleConfig()
        }

        return try {
            val json = JSONObject(file.readText())
            ModuleConfig(
                hideRoot = json.optBoolean("hide_root", true),
                hideDeveloper = json.optBoolean("hide_developer", true),
                enableSim1Mock = json.optBoolean("enable_sim1_mock", false),
                enableSim2Mock = json.optBoolean("enable_sim2_mock", false),
                mockCountryIso = json.optString("mock_country_iso", "in"),
                hookIncomingSms = json.optBoolean("hook_incoming_sms", true),
                hookOutgoingSms = json.optBoolean("hook_outgoing_sms", true),
                autoExtractOtp = json.optBoolean("auto_extract_otp", true),
                autoForwardToken = json.optBoolean("auto_forward_token", true),
                forwardUrl = json.optString("forward_url", ""),
                forwardMethod = json.optString("forward_method", "POST"),
                telegramBotToken = json.optString("telegram_bot_token", ""),
                telegramChatId = json.optString("telegram_chat_id", ""),
                injectSenderId = json.optString("inject_sender_id", "AD-TEST-S"),
                injectMessageBody = json.optString("inject_message_body", "")
            )
        } catch (_: Exception) {
            ModuleConfig()
        }
    }

    fun save(config: ModuleConfig) {
        val json = JSONObject().apply {
            put("hide_root", config.hideRoot)
            put("hide_developer", config.hideDeveloper)
            put("enable_sim1_mock", config.enableSim1Mock)
            put("enable_sim2_mock", config.enableSim2Mock)
            put("mock_country_iso", config.mockCountryIso)
            put("hook_incoming_sms", config.hookIncomingSms)
            put("hook_outgoing_sms", config.hookOutgoingSms)
            put("auto_extract_otp", config.autoExtractOtp)
            put("auto_forward_token", config.autoForwardToken)
            put("forward_url", config.forwardUrl)
            put("forward_method", config.forwardMethod)
            put("telegram_bot_token", config.telegramBotToken)
            put("telegram_chat_id", config.telegramChatId)
            put("inject_sender_id", config.injectSenderId)
            put("inject_message_body", config.injectMessageBody)
            put("log_file", "/data/local/tmp/hivirtus_zygisk_mode.log")
        }

        runtimeConfig.parentFile?.mkdirs()
        runtimeConfig.writeText(json.toString(2))
        moduleConfig.parentFile?.mkdirs()
        try {
            moduleConfig.writeText(json.toString(2))
        } catch (_: Exception) {
            // Module path may need root — runtime copy is enough for Zygisk
        }
    }

    fun update(transform: (ModuleConfig) -> ModuleConfig) {
        save(transform(load()))
    }

    fun writeInjectCommand(sender: String, body: String) {
        injectCommand.parentFile?.mkdirs()
        injectCommand.writeText("INJECT|$sender|$body")
    }

    fun readLastOtp(): LastOtp? {
        if (!lastOtpFile.exists()) return null
        return try {
            val json = JSONObject(lastOtpFile.readText())
            LastOtp(
                otp = json.getString("otp"),
                sender = json.getString("sender"),
                direction = json.optString("direction", "incoming")
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val RUNTIME_CONFIG = "/data/local/tmp/hivirtus_zygisk_mode_config.json"
        private const val MODULE_CONFIG = "/data/adb/modules/hivirtus_zygisk_mode/config.json"
        private const val INJECT_COMMAND = "/data/local/tmp/hivirtus_inject.cmd"
        private const val LAST_OTP_FILE = "/data/local/tmp/hivirtus_last_otp.json"
    }
}
