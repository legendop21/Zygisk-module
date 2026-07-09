package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Root appops — com.android.phone / Messages ko SEND_SMS deny.
 * Native hook fail ho to bhi real SIM se SMS nahi jayega.
 */
object PhoneSmsBlocker {

    private const val TAG = "PhoneSmsBlocker"
    private const val FLAG = "/data/local/tmp/hivirtus_phone_sms_denied.flag"

    private val corePackages = listOf(
        "com.android.phone",
        "com.android.providers.telephony",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.mms.service",
        "com.samsung.android.messaging"
    )

    fun enforce(context: Context) {
        val config = ConfigManager(context).load()
        val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
        val blockOn = config.hookOutgoingSms || config.interceptFakeSuccess || mockOn
        if (!blockOn) {
            restore()
            return
        }

        val packages = corePackages.toMutableList()
        ActiveHookManager.readActivePackage()?.let { packages.add(it) }
        ForegroundAppHelper.foregroundPackage(context)?.let { fg ->
            if (UpiAppRegistry.findByPackage(fg) != null) packages.add(fg)
        }

        var ok = false
        packages.distinct().forEach { pkg ->
            val denied = ShellHelper.runSu(
                "appops set $pkg SEND_SMS deny 2>/dev/null; " +
                    "appops set $pkg WRITE_SMS deny 2>/dev/null"
            )
            if (denied) ok = true
        }

        if (ok) {
            try {
                File(FLAG).writeText("1")
            } catch (_: Exception) {
                ShellHelper.runSu("echo 1 > '$FLAG' && chmod 644 '$FLAG'")
            }
            Log.i(TAG, "SEND_SMS denied for phone/messaging/UPI (${packages.size} pkgs)")
        }
    }

    private fun restore() {
        corePackages.forEach { pkg ->
            ShellHelper.runSu(
                "appops set $pkg SEND_SMS allow 2>/dev/null; " +
                    "appops set $pkg WRITE_SMS allow 2>/dev/null"
            )
        }
        try {
            File(FLAG).delete()
        } catch (_: Exception) {}
    }
}
