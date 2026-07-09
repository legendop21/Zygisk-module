package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * Mock number save hone par SIM spoof + SMS block + telephony wake ek saath.
 */
object MockSimPipeline {

    private const val TAG = "MockSimPipeline"

    fun activate(context: Context) {
        val appContext = context.applicationContext
        try {
            PhoneSmsBlocker.enforce(appContext)
            OutgoingSmsGuard.refresh(appContext)
            TelephonyInjectHelper.wakeTelephonyPipeline()
            Log.i(TAG, "Mock SIM pipeline activated (block + telephony wake)")
        } catch (e: Exception) {
            Log.w(TAG, "activate failed: ${e.message}")
        }
    }
}
