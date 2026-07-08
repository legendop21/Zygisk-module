package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")

    fun enabledHookedApps(config: ModuleConfig): List<UpiAppRegistry.UpiApp> =
        UpiAppRegistry.ALL.filter { config.hookedUpiApps[it.packageName] == true }

    fun shouldCapture(config: ModuleConfig, sender: String, body: String): Boolean {
        if (!config.hookIncomingSms && !config.hookUpiVerification) return false
        if (body.isBlank() || sender.isBlank()) return false

        val hooked = enabledHookedApps(config)
        if (hooked.isEmpty()) return false

        // Custom intercept sender ID from MESSAGE tab
        val filterSender = config.injectSenderId.trim()
        if (filterSender.isNotBlank() && filterSender != "AD-TEST-S") {
            if (senderMatches(sender, filterSender)) return true
        }

        val matchedHooked = UpiAppRegistry.matchAmong(hooked, sender, body)
        if (matchedHooked != null) return true

        if (config.hookUpiVerification && isVerificationSms(sender, body)) {
            return true
        }

        return config.hookIncomingSms && matchedHooked != null
    }

    fun senderMatches(actual: String, filter: String): Boolean {
        val a = normalizeSender(actual)
        val f = normalizeSender(filter)
        if (a.isBlank() || f.isBlank()) return false
        return a == f || a.contains(f) || f.contains(a)
    }

    fun normalizeSender(sender: String): String =
        sender.replace("\\s".toRegex(), "").uppercase()

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

    fun messageLabel(config: ModuleConfig, sender: String, body: String): String {
        val hooked = enabledHookedApps(config)
        UpiAppRegistry.matchAmong(hooked, sender, body)?.let { return it.displayName.uppercase() }
        UpiAppRegistry.matchApp(sender, body)?.let { app ->
            if (config.hookedUpiApps[app.packageName] == true) return app.displayName.uppercase()
        }
        return formatSenderLabel(sender)
    }

    fun isEncryptedToken(text: String): Boolean {
        if (text.length < 24) return false
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 20) return false
        val tokenChars = compact.count { it.isLetterOrDigit() || it in "+/=)(?&._-" }
        return tokenChars >= compact.length * 0.85
    }

    private fun isVerificationSms(sender: String, body: String): Boolean {
        if (isEncryptedToken(body)) return true
        val upperSender = sender.uppercase()
        val upperBody = body.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "YESPAY", "YESPRO",
            "PHONEPE", "PAYTM", "GPAY", "BHIM", "CODE", "PIN", "PASSWORD", "BANK"
        )
        if (keywords.any { upperBody.contains(it) || upperSender.contains(it) }) return true
        if (upperSender.startsWith("AD-") || upperSender.startsWith("VM-")) return true
        if (sender.replace("\\D".toRegex(), "").length >= 8) return true
        return false
    }

    private fun formatSenderLabel(sender: String): String {
        var label = sender.trim()
        listOf("AD-", "VM-", "JD-", "BP-", "TX-").forEach { prefix ->
            if (label.startsWith(prefix, ignoreCase = true)) label = label.substring(prefix.length)
        }
        label = label.trim('-', ' ')
        if (label.endsWith("-S", ignoreCase = true)) label = label.dropLast(2)
        return label.uppercase().ifBlank { "INTERCEPT" }
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
