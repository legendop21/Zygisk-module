package com.hivirtus.zygiskmode

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import java.io.File

/**
 * Port of SmsTweaks fake-success layer — inserts sent SMS row so UPI apps
 * see verification SMS as delivered when native hook blocks real send.
 */
object FakeSmsSuccessHelper {

    private const val TAG = "FakeSmsSuccess"
    private const val SENT_URI = "content://sms/sent"

    fun insertFakeSentSms(context: Context, dest: String, body: String): Boolean {
        if (dest.isBlank() || body.isBlank()) return false

        if (insertViaContentResolver(context, dest, body)) return true
        return insertViaSu(dest, body)
    }

    fun processInterceptFlag(context: Context) {
        val flag = File("/data/local/tmp/hivirtus_outgoing_blocked.flag")
        if (!flag.canRead()) return

        try {
            val line = flag.readText().trim()
            if (line.isBlank()) return
            val parts = line.split("|", limit = 2)
            val dest = parts.getOrElse(0) { "" }
            val body = parts.getOrElse(1) { "" }
            if (dest.isBlank() || body.isBlank()) return

            val config = ConfigManager(context).load()
            if (!config.interceptFakeSuccess) return

            insertFakeSentSms(context, dest, body)
        } catch (e: Exception) {
            Log.w(TAG, "processInterceptFlag failed: ${e.message}")
        }
    }

    private fun insertViaContentResolver(context: Context, dest: String, body: String): Boolean {
        if (!PermissionHelper.hasReadSms(context)) return false
        return try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, dest)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.STATUS, -1)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.LOCKED, 0)
                put(Telephony.Sms.ERROR_CODE, 0)
            }
            val uri = context.contentResolver.insert(Uri.parse(SENT_URI), values)
            if (uri != null) {
                Log.i(TAG, "Fake sent SMS inserted via ContentResolver")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver sent insert failed: ${e.message}")
            false
        }
    }

    private fun insertViaSu(dest: String, body: String): Boolean {
        val safeDest = dest.replace("'", "'\\''")
        val safeBody = body.replace("'", "'\\''").replace("\n", " ")
        val date = System.currentTimeMillis()
        val cmd = buildString {
            append("content insert --uri content://sms/sent ")
            append("--bind address:s:'$safeDest' ")
            append("--bind body:s:'$safeBody' ")
            append("--bind type:i:2 ")
            append("--bind read:i:1 ")
            append("--bind seen:i:1 ")
            append("--bind status:i:-1 ")
            append("--bind date:l:$date ")
            append("--bind date_sent:l:$date")
        }
        val ok = ShellHelper.runSu(cmd)
        if (ok) Log.i(TAG, "Fake sent SMS inserted via su")
        return ok
    }
}
