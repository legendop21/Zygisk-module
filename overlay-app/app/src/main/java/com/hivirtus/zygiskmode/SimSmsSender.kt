package com.hivirtus.zygiskmode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object SimSmsSender {

    private const val TAG = "SimSmsSender"

    data class SimInfo(val slot: Int, val number: String, val subscriptionId: Int)

    @SuppressLint("MissingPermission")
    fun readSimNumbers(context: Context): List<SimInfo> {
        if (!hasPhoneState(context)) return emptyList()
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        val subManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        } else {
            null
        }
        val result = mutableListOf<SimInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && subManager != null) {
            val infos = subManager.activeSubscriptionInfoList.orEmpty()
            for (info in infos.sortedBy { it.simSlotIndex }) {
                val number = info.number?.filter { it.isDigit() }.orEmpty().takeLast(10)
                result.add(
                    SimInfo(
                        slot = info.simSlotIndex,
                        number = number,
                        subscriptionId = info.subscriptionId
                    )
                )
            }
        }
        if (result.isEmpty()) {
            val fallback = tm.line1Number?.filter { it.isDigit() }.orEmpty().takeLast(10)
            if (fallback.isNotBlank()) {
                result.add(SimInfo(slot = 0, number = fallback, subscriptionId = -1))
            }
        }
        return result
    }

    fun send(context: Context, dest: String, body: String, simSlot: Int): Boolean {
        if (dest.isBlank() || body.isBlank()) return false
        if (!hasSendSms(context)) {
            Log.w(TAG, "SEND_SMS permission missing")
            return false
        }
        return try {
            val smsManager = smsManagerForSlot(context, simSlot)
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(dest, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(dest, null, body, null, null)
            }
            Log.i(TAG, "SMS sent dest=$dest sim=$simSlot parts=${parts.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun smsManagerForSlot(context: Context, simSlot: Int): SmsManager {
        val sims = readSimNumbers(context)
        val targetSubId = sims.firstOrNull { it.slot == simSlot }?.subscriptionId
            ?: sims.getOrNull(simSlot)?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        ) {
            context.getSystemService(SmsManager::class.java)
                .createForSubscriptionId(targetSubId)
        } else if (targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(targetSubId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    fun hasSendSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasPhoneState(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
