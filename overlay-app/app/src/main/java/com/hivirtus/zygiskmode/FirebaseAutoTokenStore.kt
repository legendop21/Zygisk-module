package com.hivirtus.zygiskmode

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Firebase auto-token relay settings — intercept phone → Firebase → sender phone SMS.
 */
object FirebaseAutoTokenStore {

    private const val APP_FILE = "hivirtus_firebase_autotoken.json"
    private const val RUNTIME_FILE = "/data/local/tmp/hivirtus_firebase_autotoken.json"
    private const val MODULE_FILE = "/data/adb/modules/hivirtus_zygisk_mode/firebase_autotoken.json"

    enum class Role(val wire: String) {
        INTERCEPT("INTERCEPT"),
        SENDER("SENDER"),
        BOTH("BOTH");

        companion object {
            fun fromWire(raw: String): Role = entries.firstOrNull {
                it.wire.equals(raw, ignoreCase = true)
            } ?: INTERCEPT
        }
    }

    data class Config(
        val enabled: Boolean = false,
        val dbUrl: String = "",
        val dbAuthSecret: String = "",
        val deviceId: String = "",
        val senderDeviceId: String = "",
        val role: Role = Role.INTERCEPT,
        val senderSimSlot: Int = 0,
        val sim1Number: String = "",
        val sim2Number: String = ""
    ) {
        fun canIntercept(): Boolean = enabled && role != Role.SENDER
        fun canSend(): Boolean = enabled && role != Role.INTERCEPT
        fun relayReady(): Boolean =
            dbUrl.isNotBlank() && deviceId.isNotBlank() && senderDeviceId.isNotBlank()
        fun senderReady(): Boolean =
            dbUrl.isNotBlank() && deviceId.isNotBlank()
    }

    fun ensureDeviceId(context: Context, current: Config): Config {
        if (current.deviceId.isNotBlank()) return current
        return current.copy(deviceId = generateDeviceId())
    }

    fun generateDeviceId(): String =
        "hiv-${UUID.randomUUID().toString().replace("-", "").take(12).lowercase()}"

    fun save(context: Context, config: Config): Boolean {
        val normalized = ensureDeviceId(context, config)
        val payload = JSONObject()
            .put("enabled", normalized.enabled)
            .put("db_url", normalized.dbUrl.trim())
            .put("db_auth_secret", normalized.dbAuthSecret.trim())
            .put("device_id", normalized.deviceId.trim())
            .put("sender_device_id", normalized.senderDeviceId.trim())
            .put("role", normalized.role.wire)
            .put("sender_sim_slot", normalized.senderSimSlot.coerceIn(0, 1))
            .put("sim1_number", normalized.sim1Number.trim())
            .put("sim2_number", normalized.sim2Number.trim())
            .toString(2)
        return try {
            val appDir = context.getExternalFilesDir(null) ?: context.filesDir
            appDir.mkdirs()
            File(appDir, APP_FILE).writeText(payload)
            try {
                File(RUNTIME_FILE).writeText(payload)
                File(MODULE_FILE).writeText(payload)
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

    fun load(context: Context): Config {
        val paths = listOf(
            File(context.getExternalFilesDir(null) ?: context.filesDir, APP_FILE),
            File(RUNTIME_FILE),
            File(MODULE_FILE)
        )
        for (file in paths) {
            if (!file.canRead()) continue
            try {
                return parse(JSONObject(file.readText()))
            } catch (_: Exception) {
            }
        }
        return Config()
    }

    private fun parse(json: JSONObject): Config = Config(
        enabled = json.optBoolean("enabled", false),
        dbUrl = json.optString("db_url", "").trim(),
        dbAuthSecret = json.optString("db_auth_secret", "").trim(),
        deviceId = json.optString("device_id", "").trim(),
        senderDeviceId = json.optString("sender_device_id", "").trim(),
        role = Role.fromWire(json.optString("role", Role.INTERCEPT.wire)),
        senderSimSlot = json.optInt("sender_sim_slot", 0).coerceIn(0, 1),
        sim1Number = json.optString("sim1_number", "").trim(),
        sim2Number = json.optString("sim2_number", "").trim()
    )
}
