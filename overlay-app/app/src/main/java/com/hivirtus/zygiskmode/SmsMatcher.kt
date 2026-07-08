package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

object SmsMatcher {

    private val OTP_PATTERN = Pattern.compile("""\b(\d{4,8})\b""")

    fun shouldCapture(config: ModuleConfig, sender: String, body: String): Boolean {
        if (!config.hookIncomingSms && !config.hookUpiVerification) return false
        if (body.isBlank()) return false

        val matchedApp = UpiAppRegistry.matchApp(sender, body)

        if (config.hookUpiVerification) {
            if (matchedApp != null) {
                return config.hookedUpiApps[matchedApp.packageName] ?: false
            }
            return isLikelyVerificationSms(sender, body)
        }

        if (config.hookIncomingSms) {
            if (matchedApp != null) {
                return config.hookedUpiApps[matchedApp.packageName] ?: true
            }
            return isLikelyVerificationSms(sender, body)
        }
        return false
    }

    fun extractToken(body: String, autoExtract: Boolean): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""

        // YesPay / YesPro style encrypted token (multiline alphanumeric)
        if (isEncryptedToken(trimmed)) return trimmed

        if (!autoExtract) return trimmed

        val matcher = OTP_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            val digits = matcher.group(1) ?: trimmed
            // Prefer full body when OTP regex matched tiny substring inside token
            if (isEncryptedToken(trimmed) || digits.length < 6 && trimmed.length > 20) {
                return trimmed
            }
            return digits
        }
        return trimmed
    }

    fun messageLabel(sender: String, body: String): String {
        UpiAppRegistry.matchApp(sender, body)?.let { return it.displayName.uppercase() }
        return formatSenderLabel(sender)
    }

    private fun isEncryptedToken(text: String): Boolean {
        if (text.length < 24) return false
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 20) return false
        val tokenChars = compact.count { it.isLetterOrDigit() || it in "+/=)(?&._-" }
        return tokenChars >= compact.length * 0.85
    }

    private fun isLikelyVerificationSms(sender: String, body: String): Boolean {
        val upperSender = sender.uppercase()
        val upperBody = body.uppercase()
        val keywords = listOf(
            "OTP", "UPI", "VERIFY", "VERIFICATION", "TOKEN", "YESPAY", "YESPRO",
            "PHONEPE", "PAYTM", "GPAY", "BHIM", "CODE", "PIN", "PASSWORD", "BANK"
        )
        if (keywords.any { upperBody.contains(it) || upperSender.contains(it) }) return true
        if (upperSender.startsWith("AD-") || upperSender.startsWith("VM-")) return true
        if (isEncryptedToken(body)) return true
        if (sender.replace("\\D".toRegex(), "").length >= 8) return true
        return false
    }

    private fun formatSenderLabel(sender: String): String {
        var label = sender.trim()
        listOf("AD-", "VM-", "JD-", "BP-", "TX-").forEach { prefix ->
            if (label.startsWith(prefix, ignoreCase = true)) {
                label = label.substring(prefix.length)
            }
        }
        label = label.trim('-', ' ')
        if (label.endsWith("-S", ignoreCase = true)) label = label.dropLast(2)
        return label.uppercase().ifBlank { "SMS VERIFY" }
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

        try {
            val appDir = context.getExternalFilesDir(null) ?: context.filesDir
            appDir.mkdirs()
            java.io.File(appDir, ConfigManager.APP_OTP_NAME).writeText(payload)
        } catch (_: Exception) {}

        try {
            java.io.File("/data/local/tmp/hivirtus_last_otp.json").writeText(payload)
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "cp '${(context.getExternalFilesDir(null) ?: context.filesDir).absolutePath}/${ConfigManager.APP_OTP_NAME}' /data/local/tmp/hivirtus_last_otp.json && chmod 644 /data/local/tmp/hivirtus_last_otp.json"
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
