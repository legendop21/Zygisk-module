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
        val phone = otp.phone.ifBlank { configManager.readSpoofPhone() }

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

        val phone = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
        val text = buildString {
            appendLine("Hivirtus Zygisk Mode — Test OK")
            appendLine("Root Hide: Active")
            appendLine("Detected: ${configManager.readRootType()}")
            appendLine("Fake Number: $phone")
            appendLine("Token forward ready!")
        }

        return postTelegram(botToken, chatId, text)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp, phone: String): Boolean {
        val text = buildString {
            appendLine("Hivirtus Token Forward")
            appendLine("OTP: ${otp.otp}")
            appendLine("Token: ${otp.otp}")
            appendLine("Sender: ${otp.sender}")
            appendLine("Phone: $phone")
            appendLine("Direction: ${otp.direction}")
            appendLine("Root: ${configManager.readRootType()} Hidden")
        }
        return postTelegram(botToken, chatId, text)
    }

    private fun postTelegram(botToken: String, chatId: String, text: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("parse_mode", "HTML")
            .toString()
        return postJson(url, payload)
    }

    private fun forwardWebhook(url: String, method: String, otp: LastOtp, phone: String): Boolean {
        val payload = JSONObject()
            .put("otp", otp.otp)
            .put("token", otp.otp)
            .put("sender", otp.sender)
            .put("phone", phone)
            .put("direction", otp.direction)
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
