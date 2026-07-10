package com.hivirtus.zygiskmode

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * SMS Modifier style — fake sent row so apps checking sent folder see success.
 */
object FakeSentSmsHelper {

    private const val TAG = "FakeSentSms"

    fun insertFakeSentSms(context: Context, dest: String, body: String): Boolean {
        if (dest.isBlank()) return false
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, dest)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            val uri = context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            val ok = uri != null
            Log.i(TAG, "insertFakeSentSms dest=$dest ok=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "insertFakeSentSms failed: ${e.message}")
            false
        }
    }
}
