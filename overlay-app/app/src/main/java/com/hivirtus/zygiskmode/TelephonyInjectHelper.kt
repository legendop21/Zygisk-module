package com.hivirtus.zygiskmode

import android.util.Log

/**
 * Native inject_sms pipeline sirf com.android.phone start pe chalti hai —
 * inject cmd likhne ke baad phone process ko wake karo.
 */
object TelephonyInjectHelper {

    private const val TAG = "TelephonyInjectHelper"

    fun wakeTelephonyPipeline() {
        try {
            val ok = ShellHelper.runSu(
                "kill \$(pidof com.android.phone) 2>/dev/null || " +
                    "am force-stop com.android.phone 2>/dev/null"
            )
            Log.i(TAG, "Telephony wake requested ok=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "Telephony wake failed: ${e.message}")
        }
    }
}
