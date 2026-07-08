package com.hivirtus.zygiskmode

/**
 * Sender ID change ke baad Messages + telephony restart — notification bar update.
 */
object SmsStackRefresher {

    fun refreshAfterSenderIdChange() {
        TelephonyInjectHelper.wakeTelephonyPipeline()
        ShellHelper.runSu(
            "am force-stop com.google.android.apps.messaging 2>/dev/null; " +
                "am force-stop com.android.mms 2>/dev/null; " +
                "am force-stop com.samsung.android.messaging 2>/dev/null"
        )
    }
}
