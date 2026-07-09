package com.hivirtus.zygiskmode.xposed

import com.hivirtus.zygiskmode.ModuleConfig
import com.hivirtus.zygiskmode.SmsMatcher
import com.hivirtus.zygiskmode.UpiAppRegistry
import org.json.JSONObject
import java.io.File

object XposedConfigBridge {

    private const val RUNTIME = "/data/local/tmp/hivirtus_zygisk_mode_config.json"
    private const val MODULE = "/data/adb/modules/hivirtus_zygisk_mode/config.json"
    private const val SPOOF_PHONE = "/data/local/tmp/hivirtus_spoof_phone.txt"
    private const val APP_DATA =
        "/sdcard/Android/data/com.hivirtus.zygiskmode/files/hivirtus_zygisk_mode_config.json"

    val systemPackages = setOf(
        "android",
        "com.android.phone",
        "com.android.providers.telephony",
        "com.google.android.gms",
        "com.google.android.gms.persistent",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.android.mms.service"
    )

    @Volatile
    private var cached: ModuleConfig? = null

    @Volatile
    private var cachedAt = 0L

    fun load(force: Boolean = false): ModuleConfig {
        val now = System.currentTimeMillis()
        if (!force && cached != null && now - cachedAt < 800L) {
            return cached!!
        }
        val file = listOf(RUNTIME, MODULE, APP_DATA).firstOrNull { File(it).canRead() }
        val parsed = if (file != null) {
            runCatching { parse(JSONObject(File(file).readText())) }.getOrNull()
        } else {
            null
        }
        val merged = mergeWithSpoofFile(parsed ?: ModuleConfig())
        cached = merged
        cachedAt = now
        return merged
    }

    fun fallbackConfig(): ModuleConfig = mergeWithSpoofFile(ModuleConfig(
        enablePhoneSpoof = true,
        enableSim1Mock = true,
        interceptFakeSuccess = true,
        hookOutgoingSms = true,
        hookIncomingSms = true,
        hookUpiVerification = true,
        overrideIncomingSender = true,
        hookedUpiApps = UpiAppRegistry.ALL.associate { it.packageName to true }
    ))

    fun anyHooked(config: ModuleConfig): Boolean =
        config.hookedUpiApps.any { it.value } || readSpoofPhone(config).isNotBlank()

    fun isTargetPackage(packageName: String, config: ModuleConfig): Boolean {
        if (packageName in systemPackages) return true
        if (config.hookedUpiApps[packageName] == true) return true
        val phone = readSpoofPhone(config)
        if (phone.isBlank()) return false
        if (config.interceptFakeSuccess || config.enablePhoneSpoof || config.enableSim1Mock) {
            return UpiAppRegistry.ALL.any { it.packageName == packageName } ||
                config.hookedUpiApps.any { it.key == packageName && it.value }
        }
        return false
    }

    fun isUpiApp(packageName: String, config: ModuleConfig): Boolean =
        config.hookedUpiApps[packageName] == true ||
            UpiAppRegistry.ALL.any { it.packageName == packageName }

    fun readSpoofPhone(config: ModuleConfig): String {
        listOf(SPOOF_PHONE, "/data/adb/modules/hivirtus_zygisk_mode/spoof_phone.txt")
            .forEach { path ->
                try {
                    val value = File(path).readText().trim()
                    if (value.isNotBlank()) return value
                } catch (_: Exception) {
                }
            }
        return config.mockPhoneSim1.trim()
    }

    fun shouldBlockOutgoing(config: ModuleConfig, dest: String, body: String): Boolean {
        if (!config.interceptFakeSuccess && !config.hookOutgoingSms) return false
        if (body.isBlank()) return false
        if (!anyHooked(config) && readSpoofPhone(config).isBlank()) return false

        if (config.interceptFakeSuccess) {
            if (bodyHasVerifyToken(body)) return true
            if (SmsMatcher.isShortCodeRecipient(dest)) return true
            if (dest.isNotBlank() && body.length >= 4) return true
        }
        return SmsMatcher.shouldInterceptOutgoing(config, dest, body)
    }

    fun shouldSimSpoof(config: ModuleConfig): Boolean {
        if (readSpoofPhone(config).isNotBlank()) return true
        return config.enableVirtualSim || config.enablePhoneSpoof || config.enableSim1Mock || config.enableSim2Mock
    }

    private fun bodyHasVerifyToken(body: String): Boolean {
        val upper = body.uppercase()
        val keys = listOf(
            "YESPRO", "YESPROUPI", "YESPAY", "YESBNK", "PHONEPE", "PAYTM", "GPAY",
            "SNAPMINT", "KREDIT", "UPI", "VERIFY", "OTP", "MEDIBUDDY", "HEROAXIS",
            "AXIS", "AIRTEL", "HDFC", "VK-"
        )
        return keys.any { upper.contains(it) }
    }

    private fun mergeWithSpoofFile(base: ModuleConfig): ModuleConfig {
        val phone = readSpoofPhone(base)
        if (phone.isBlank()) return base
        return base.copy(
            mockPhoneSim1 = phone,
            enablePhoneSpoof = true,
            enableSim1Mock = true,
            interceptFakeSuccess = true,
            hookOutgoingSms = true,
            hookIncomingSms = true
        )
    }

    private fun parse(json: JSONObject): ModuleConfig {
        val hooked = mutableMapOf<String, Boolean>()
        UpiAppRegistry.ALL.forEach { app ->
            hooked[app.packageName] = false
        }
        if (json.has("hooked_upi_apps")) {
            val obj = json.getJSONObject("hooked_upi_apps")
            hooked.keys.forEach { pkg ->
                hooked[pkg] = obj.optBoolean(pkg, false)
            }
        }

        return ModuleConfig(
            hideRoot = json.optBoolean("hide_root", false),
            hideDeveloper = json.optBoolean("hide_developer", false),
            enableVirtualSim = json.optBoolean("enable_virtual_sim", false) ||
                json.optBoolean("enable_sim1_mock", false) ||
                json.optBoolean("enable_phone_spoof", false),
            enableSim1Mock = json.optBoolean("enable_sim1_mock", false),
            enableSim2Mock = json.optBoolean("enable_sim2_mock", false),
            enablePhoneSpoof = json.optBoolean("enable_phone_spoof", false),
            mockCountryIso = json.optString("mock_country_iso", "in"),
            mockPhoneSim1 = json.optString("mock_phone_sim1", ""),
            mockPhoneSim2 = json.optString("mock_phone_sim2", ""),
            hookIncomingSms = json.optBoolean("hook_incoming_sms", true),
            hookOutgoingSms = json.optBoolean("hook_outgoing_sms", true),
            hookUpiVerification = json.optBoolean("hook_upi_verification", true),
            hookedUpiApps = hooked,
            autoExtractOtp = json.optBoolean("auto_extract_otp", true),
            autoForwardToken = json.optBoolean("auto_forward_token", true),
            injectSenderId = json.optString("inject_sender_id", ""),
            injectMessageBody = json.optString("inject_message_body", ""),
            overrideIncomingSender = json.optBoolean("override_incoming_sender", true),
            interceptFakeSuccess = json.optBoolean("intercept_fake_success", true),
            autoHookForeground = json.optBoolean("auto_hook_foreground", true)
        )
    }
}
