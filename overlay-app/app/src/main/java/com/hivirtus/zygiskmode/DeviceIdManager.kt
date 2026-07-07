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
        if (config.spoofAndroidId.isNotBlank()) return config.spoofAndroidId
        return generateNewAndroidId()
    }

    private fun writeRuntimeId(id: String) {
        File("/data/local/tmp/hivirtus_spoof_android_id.txt").writeText(id)
        File("/data/local/tmp/hivirtus_change_device_id.cmd").writeText("CHANGE_ID|$id")
    }
}
