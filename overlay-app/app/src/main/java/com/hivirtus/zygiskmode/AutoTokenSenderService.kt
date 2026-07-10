package com.hivirtus.zygiskmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Sender phone: Firebase commands poll karke auto SMS bhejta hai.
 */
class AutoTokenSenderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private val processed = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startWorkers()
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWorkers() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(POLL_MS)
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching { heartbeat() }
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun tick() {
        val config = FirebaseAutoTokenStore.load(this)
        if (!config.canSend() || !config.senderReady()) return

        val client = FirebaseRestClient(config)
        val commands = client.get("hivirtus/commands/${config.deviceId}") ?: return

        val keys = commands.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in processed) continue
            val node = commands.optJSONObject(key) ?: continue
            if (!isPending(node)) continue

            val dest = resolveDest(node)
            val body = resolveBody(node)
            if (dest.isBlank() || body.isBlank()) {
                markFailed(client, config.deviceId, key, "missing dest/body")
                processed.add(key)
                continue
            }

            val simSlot = resolveSimSlot(node, config.senderSimSlot)
            val sent = SimSmsSender.send(this, dest, body, simSlot)
            if (sent) {
                markSent(client, config.deviceId, key, simSlot)
                Log.i(TAG, "Auto-sent dest=$dest sim=$simSlot")
            } else {
                markFailed(client, config.deviceId, key, "sms_send_failed")
            }
            processed.add(key)
            trimProcessed()
        }
    }

    private fun heartbeat() {
        val config = FirebaseAutoTokenStore.load(this)
        if (!config.canSend() || !config.senderReady()) return

        val sims = SimSmsSender.readSimNumbers(this)
        val sim1 = sims.firstOrNull { it.slot == 0 }?.number.orEmpty()
            .ifBlank { config.sim1Number }
        val sim2 = sims.firstOrNull { it.slot == 1 }?.number.orEmpty()
            .ifBlank { config.sim2Number }

        val payload = linkedMapOf<String, Any?>(
            "deviceId" to config.deviceId,
            "online" to true,
            "lastSeen" to System.currentTimeMillis(),
            "role" to config.role.wire,
            "sim1" to sim1,
            "sim2" to sim2,
            "senderSim" to if (config.senderSimSlot == 0) "SIM1" else "SIM2",
            "model" to Build.MODEL
        )
        FirebaseRestClient(config).put("hivirtus/devices/${config.deviceId}", payload)
    }

    private fun isPending(node: JSONObject): Boolean {
        val status = node.optString("status", "pending").lowercase()
        return status == "pending" || status.isBlank()
    }

    private fun resolveDest(node: JSONObject): String =
        node.optString("to")
            .ifBlank { node.optString("recipient") }
            .trim()

    private fun resolveBody(node: JSONObject): String =
        node.optString("body")
            .ifBlank { node.optString("message") }
            .trim()

    private fun resolveSimSlot(node: JSONObject, defaultSlot: Int): Int {
        if (node.has("sim")) {
            return node.optInt("sim", defaultSlot).coerceIn(0, 1)
        }
        return when (node.optString("targetSim", "").uppercase()) {
            "SIM2", "1" -> 1
            "SIM1", "0" -> 0
            else -> defaultSlot.coerceIn(0, 1)
        }
    }

    private fun markSent(client: FirebaseRestClient, deviceId: String, key: String, simSlot: Int) {
        client.patch(
            "hivirtus/commands/$deviceId/$key",
            mapOf(
                "status" to "sent",
                "sent_at" to System.currentTimeMillis(),
                "sim_used" to if (simSlot == 0) "SIM1" else "SIM2"
            )
        )
    }

    private fun markFailed(client: FirebaseRestClient, deviceId: String, key: String, reason: String) {
        client.patch(
            "hivirtus/commands/$deviceId/$key",
            mapOf(
                "status" to "failed",
                "error" to reason,
                "failed_at" to System.currentTimeMillis()
            )
        )
    }

    private fun trimProcessed() {
        while (processed.size > 200) {
            val first = processed.iterator().next()
            processed.remove(first)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.autotoken_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.autotoken_service_title))
            .setContentText(getString(R.string.autotoken_service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AutoTokenSender"
        private const val CHANNEL_ID = "hivirtus_autotoken_sender"
        private const val NOTIFICATION_ID = 6606
        private const val POLL_MS = 3_000L
        private const val HEARTBEAT_MS = 30_000L
        const val ACTION_STOP = "com.hivirtus.zygiskmode.AUTOTOKEN_STOP"

        fun sync(context: Context) {
            val config = FirebaseAutoTokenStore.load(context)
            if (config.canSend() && config.senderReady()) {
                val intent = Intent(context, AutoTokenSenderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(Intent(context, AutoTokenSenderService::class.java))
            }
        }
    }
}
