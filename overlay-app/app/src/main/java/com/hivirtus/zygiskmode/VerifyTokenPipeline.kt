package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * UPI verify token flow:
 * 1) App ko fake success (native ISms block)
 * 2) Token Telegram pe
 * 3) User app-login wale number / 2nd SIM se manually SMS bheje
 */
object VerifyTokenPipeline {

    private const val TAG = "VerifyTokenPipeline"
    private const val PENDING_FILE = "/data/local/tmp/hivirtus_pending_verify.json"

    data class PendingVerify(
        val dest: String,
        val body: String,
        val sendFrom: String,
        val appLabel: String,
        val capturedAt: Long
    )

    fun handleBlocked(
        context: Context,
        dest: String,
        body: String,
        appLabel: String = ""
    ) {
        val appContext = context.applicationContext
        val configManager = ConfigManager(appContext)
        val config = configManager.load()
        val sendFrom = configManager.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
        val label = appLabel.ifBlank {
            ActiveHookManager.readActivePackage()?.let { UpiAppRegistry.displayNameFor(it) }.orEmpty()
        }

        val pending = PendingVerify(
            dest = dest.trim(),
            body = body.trim(),
            sendFrom = sendFrom,
            appLabel = label,
            capturedAt = System.currentTimeMillis()
        )
        savePending(pending)

        val otp = LastOtp(
            otp = SmsMatcher.extractToken(body, config.autoExtractOtp),
            sender = SmsMatcher.interceptDisplay(config, dest, configManager),
            body = body,
            phone = sendFrom,
            messageLabel = label,
            direction = "outgoing",
            rawPeer = dest,
            capturedAt = pending.capturedAt
        )
        OtpCaptureWriter.write(appContext, otp)
        ClipboardCopyHelper.copySms(appContext, body)
        TokenForwarder(configManager, appContext).forwardOutgoingBlocked(otp)

        try {
            File("/data/local/tmp/hivirtus_outgoing_fake_ok.flag").writeText("ok")
        } catch (_: Exception) {}

        Toast.makeText(
            appContext,
            appContext.getString(R.string.verify_token_copied, sendFrom.ifBlank { "registered SIM" }),
            Toast.LENGTH_LONG
        ).show()
        Log.i(TAG, "Verify blocked dest=$dest body=${body.take(24)} sendFrom=$sendFrom")
    }

    fun readPending(): PendingVerify? {
        val file = File(PENDING_FILE)
        if (!file.canRead()) return null
        return try {
            val json = JSONObject(file.readText())
            PendingVerify(
                dest = json.optString("dest"),
                body = json.optString("body"),
                sendFrom = json.optString("send_from"),
                appLabel = json.optString("app"),
                capturedAt = json.optLong("captured_at", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun savePending(pending: PendingVerify) {
        val payload = JSONObject()
            .put("dest", pending.dest)
            .put("body", pending.body)
            .put("send_from", pending.sendFrom)
            .put("app", pending.appLabel)
            .put("captured_at", pending.capturedAt)
            .toString(2)
        try {
            File(PENDING_FILE).writeText(payload)
        } catch (_: Exception) {
            ShellHelper.runSu(
                "echo '${payload.replace("'", "'\\''")}' > '$PENDING_FILE' && chmod 644 '$PENDING_FILE'"
            )
        }
    }

    /** Messages kholo — user 2nd SIM (app login number) select karke bhejega. */
    fun openMessagesCompose(context: Context, dest: String, body: String): Boolean {
        if (dest.isBlank() || body.isBlank()) return false
        return try {
            val uri = Uri.parse("smsto:${Uri.encode(dest)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Compose failed: ${e.message}")
            false
        }
    }
}
