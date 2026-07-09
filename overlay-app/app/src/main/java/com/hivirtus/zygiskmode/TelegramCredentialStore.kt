package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Bot token + chat ID — alag file, kabhi module config sync se wipe nahi hoga.
 */
object TelegramCredentialStore {

    private const val APP_FILE = "hivirtus_telegram_credentials.json"
    private const val RUNTIME_FILE = "/data/local/tmp/hivirtus_telegram_credentials.json"
    private const val MODULE_FILE = "/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json"

    data class Credentials(val botToken: String, val chatId: String)

    fun save(context: Context, botToken: String, chatId: String): Boolean {
        val token = botToken.trim()
        val chat = chatId.trim()
        if (token.isBlank() || chat.isBlank()) return false
        val payload = JSONObject()
            .put("telegram_bot_token", token)
            .put("telegram_chat_id", chat)
            .toString(2)
        return try {
            val appDir = context.getExternalFilesDir(null) ?: context.filesDir
            appDir.mkdirs()
            File(appDir, APP_FILE).writeText(payload)
            try {
                File(RUNTIME_FILE).writeText(payload)
            } catch (_: Exception) {
                val escaped = payload.replace("'", "'\\''")
                ShellHelper.runSu(
                    "echo '$escaped' > '$RUNTIME_FILE' 2>/dev/null; chmod 666 '$RUNTIME_FILE' 2>/dev/null; " +
                        "mkdir -p /data/adb/modules/hivirtus_zygisk_mode 2>/dev/null; " +
                        "cp '$RUNTIME_FILE' '$MODULE_FILE' 2>/dev/null; chmod 644 '$MODULE_FILE' 2>/dev/null"
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(context: Context): Credentials {
        val paths = listOf(
            File(context.getExternalFilesDir(null) ?: context.filesDir, APP_FILE),
            File(RUNTIME_FILE),
            File(MODULE_FILE)
        )
        for (file in paths) {
            if (!file.canRead()) continue
            try {
                val json = JSONObject(file.readText())
                val token = json.optString("telegram_bot_token", "").trim()
                val chat = json.optString("telegram_chat_id", "").trim()
                if (token.isNotBlank() && chat.isNotBlank()) {
                    return Credentials(token, chat)
                }
            } catch (_: Exception) {
            }
        }
        return Credentials("", "")
    }
}
