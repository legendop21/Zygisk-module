package com.zygisk.modmenu

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ModuleConfig(
    val hookIncomingSms: Boolean = true,
    val hookOutgoingSms: Boolean = true,
    val autoExtractOtp: Boolean = true,
    val autoForwardToken: Boolean = true,
    val forwardUrl: String = "",
    val forwardMethod: String = "POST",
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
                hookIncomingSms = json.optBoolean("hook_incoming_sms", true),
                hookOutgoingSms = json.optBoolean("hook_outgoing_sms", true),
                autoExtractOtp = json.optBoolean("auto_extract_otp", true),
                autoForwardToken = json.optBoolean("auto_forward_token", true),
                forwardUrl = json.optString("forward_url", ""),
                forwardMethod = json.optString("forward_method", "POST"),
                injectSenderId = json.optString("inject_sender_id", "AD-TEST-S"),
                injectMessageBody = json.optString("inject_message_body", "")
            )
        } catch (_: Exception) {
            ModuleConfig()
        }
    }

    fun update(transform: (ModuleConfig) -> ModuleConfig) {
        val current = load()
        val updated = transform(current)
        val json = JSONObject().apply {
            put("hook_incoming_sms", updated.hookIncomingSms)
            put("hook_outgoing_sms", updated.hookOutgoingSms)
            put("auto_extract_otp", updated.autoExtractOtp)
            put("auto_forward_token", updated.autoForwardToken)
            put("forward_url", updated.forwardUrl)
            put("forward_method", updated.forwardMethod)
            put("inject_sender_id", updated.injectSenderId)
            put("inject_message_body", updated.injectMessageBody)
            put("log_file", "/data/local/tmp/zygisk_sms_otp.log")
        }

        runtimeConfig.parentFile?.mkdirs()
        runtimeConfig.writeText(json.toString(2))
        moduleConfig.parentFile?.mkdirs()
        moduleConfig.writeText(json.toString(2))
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
        private const val RUNTIME_CONFIG = "/data/local/tmp/zygisk_sms_otp_config.json"
        private const val MODULE_CONFIG = "/data/adb/modules/zygisk_sms_otp/config.json"
        private const val INJECT_COMMAND = "/data/local/tmp/zygisk_sms_otp_inject.cmd"
        private const val LAST_OTP_FILE = "/data/local/tmp/zygisk_sms_otp_last.json"
    }
}
