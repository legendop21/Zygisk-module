package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File
import java.util.UUID

class DeviceIdManager(private val context: Context) {

    private val configManager = ConfigManager(context)

    fun generateNewAndroidId(): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16).lowercase()
        configManager.update { it.copy(spoofAndroidId = id, enableDeviceIdSpoof = true) }
        writeRuntimeId(id)
        return id
    }

    fun getCurrentSpoofId(): String {
        val config = configManager.load()
        return config.spoofAndroidId.ifBlank { "Not set" }
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
            File("/data/local/tmp/hivirtus_spoof_android_id.txt").writeText(id)
        } catch (_: Exception) {}
        try {
            File("/data/local/tmp/hivirtus_change_device_id.cmd").writeText("CHANGE_ID|$id")
        } catch (_: Exception) {
            runSu("echo 'CHANGE_ID|$id' > /data/local/tmp/hivirtus_change_device_id.cmd")
        }
    }

    private fun runSu(command: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
