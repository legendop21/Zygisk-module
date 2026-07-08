package com.hivirtus.zygiskmode.xposed

import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HookDebug {

    private const val LOG_FILE = "/data/local/tmp/hivirtus_hook_debug.log"
    private const val MAX_BYTES = 512_000L

    @Volatile
    var enabled = true

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${timestamp()} [$tag] $message"
        XposedBridge.log("Hivirtus|$line")
        appendFile(line)
    }

    fun logHook(pkg: String, hook: String) {
        log(pkg, "HOOK OK → $hook")
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun appendFile(line: String) {
        try {
            val file = File(LOG_FILE)
            if (file.length() > MAX_BYTES) {
                file.writeText("")
            }
            file.appendText("$line\n")
            Runtime.getRuntime().exec(arrayOf("chmod", "666", LOG_FILE)).waitFor()
        } catch (_: Exception) {
        }
    }
}
