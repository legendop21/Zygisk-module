package com.hivirtus.zygiskmode

import org.json.JSONObject
import java.io.File

/** Native / service.sh blocked outgoing SMS — dest + full body (pipes/newlines safe). */
object OutgoingBlockedReader {

    private const val FLAG_LEGACY = "/data/local/tmp/hivirtus_outgoing_blocked.flag"
    private const val FLAG_JSON = "/data/local/tmp/hivirtus_outgoing_blocked.json"

    data class Blocked(val dest: String, val body: String)

    fun read(): Blocked? {
        readJson()?.let { return it }
        return readLegacy()
    }

    fun consume(context: android.content.Context? = null): Blocked? {
        val blocked = read() ?: return null
        deleteFlags()
        context?.applicationContext?.let { ctx ->
            VerifyTokenPipeline.handleBlocked(ctx, blocked.dest, blocked.body)
        }
        return blocked
    }

    fun deleteFlags() {
        try { File(FLAG_LEGACY).delete() } catch (_: Exception) {}
        try { File(FLAG_JSON).delete() } catch (_: Exception) {}
    }

    private fun readJson(): Blocked? {
        val file = File(FLAG_JSON)
        if (!file.canRead()) return null
        return try {
            val json = JSONObject(file.readText())
            val dest = json.optString("dest", "").trim()
            val body = json.optString("body", "").trim()
            if (body.isBlank()) null else Blocked(dest, body)
        } catch (_: Exception) {
            null
        }
    }

    private fun readLegacy(): Blocked? {
        val file = File(FLAG_LEGACY)
        if (!file.canRead()) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            val nl = text.indexOf('\n')
            if (nl >= 0) {
                val dest = text.substring(0, nl).trim()
                val body = text.substring(nl + 1).trim()
                if (body.isBlank()) null else Blocked(dest, body)
            } else {
                val parts = text.trim().split("|", limit = 2)
                val body = parts.getOrElse(1) { "" }.trim()
                if (body.isBlank()) null else Blocked(parts.getOrElse(0) { "" }.trim(), body)
            }
        } catch (_: Exception) {
            null
        }
    }
}
