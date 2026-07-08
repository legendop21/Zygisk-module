package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File
import java.util.UUID

class DeviceIdManager(private val context: Context) {

    private val configManager = ConfigManager(context)

    fun generateNewAndroidId(): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16).lowercase()
        configManager.update { it.copy(spoofAndroidId = id, enableDeviceIdSpoof = true) }
        persistDeviceId(id)
        applyAndroidId(id)
        return id
    }

    fun getCurrentSpoofId(): String {
        val config = configManager.load()
        if (config.spoofAndroidId.isNotBlank()) return config.spoofAndroidId
        return readPersistedId().ifBlank { "Not set" }
    }

    fun applyAndroidId(id: String) {
        if (id.isBlank()) return
        persistDeviceId(id)
        val escaped = id.replace("'", "'\\''")
        val commands = listOf(
            "settings put secure android_id '$escaped'",
            "resetprop ro.serialno '$escaped'",
            "resetprop ro.boot.serialno '$escaped'"
        )
        commands.forEach { runSu(it) }
    }

    private fun persistDeviceId(id: String) {
        writeRuntimeId(id)
        writeModulePersistedId(id)
        syncModuleConfig(id)
    }

    private fun writeRuntimeId(id: String) {
        val appFile = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "hivirtus_spoof_android_id.txt"
        )
        try {
            appFile.parentFile?.mkdirs()
            appFile.writeText(id)
        } catch (_: Exception) {}
        try {
            File(RUNTIME_ID_FILE).writeText(id)
        } catch (_: Exception) {
            runSu("echo '$id' > '$RUNTIME_ID_FILE' && chmod 644 '$RUNTIME_ID_FILE'")
        }
        try {
            File(CHANGE_CMD_FILE).writeText("CHANGE_ID|$id")
        } catch (_: Exception) {
            runSu("echo 'CHANGE_ID|$id' > '$CHANGE_CMD_FILE' && chmod 644 '$CHANGE_CMD_FILE'")
        }
    }

    private fun writeModulePersistedId(id: String) {
        try {
            File(MODULE_ID_FILE).parentFile?.mkdirs()
            File(MODULE_ID_FILE).writeText(id)
        } catch (_: Exception) {
            runSu("mkdir -p /data/adb/modules/hivirtus_zygisk_mode && echo '$id' > '$MODULE_ID_FILE' && chmod 644 '$MODULE_ID_FILE'")
        }
    }

    private fun syncModuleConfig(id: String) {
        val config = configManager.load().copy(
            spoofAndroidId = id,
            enableDeviceIdSpoof = true
        )
        configManager.save(config)
    }

    private fun readPersistedId(): String {
        val paths = listOf(
            File(MODULE_ID_FILE),
            File(RUNTIME_ID_FILE),
            File("/data/adb/modules/hivirtus_zygisk_mode/config.json")
        )
        for (file in paths) {
            try {
                if (!file.canRead()) continue
                if (file.name.endsWith(".json")) {
                    val text = file.readText()
                    val match = Regex("\"spoof_android_id\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    if (match != null && match.groupValues[1].isNotBlank()) {
                        return match.groupValues[1]
                    }
                } else {
                    val text = file.readText().trim()
                    if (text.isNotBlank()) return text
                }
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun runSu(command: String): Boolean = ShellHelper.runSu(command)

    companion object {
        private const val MODULE_ID_FILE = "/data/adb/modules/hivirtus_zygisk_mode/spoof_android_id.txt"
        private const val RUNTIME_ID_FILE = "/data/local/tmp/hivirtus_spoof_android_id.txt"
        private const val CHANGE_CMD_FILE = "/data/local/tmp/hivirtus_change_device_id.cmd"
    }
}
