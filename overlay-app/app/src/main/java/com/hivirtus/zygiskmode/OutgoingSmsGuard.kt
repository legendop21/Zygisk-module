package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Block real verify SMS from Messages / Phone / UPI apps — native ISms hook ke saath.
 */
object OutgoingSmsGuard {

    private const val TAG = "OutgoingSmsGuard"

    private val messagingPackages = listOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.mms.service",
        "com.samsung.android.messaging",
        "com.android.mms.service"
    )

    private val upiSmsSenders = listOf(
        "com.kreditbee.android",
        "com.mobikwik_new",
        "net.one97.paytm",
        "com.fampay.in",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "com.stashfin.android",
        "com.yespay.next",
        "com.herofincorp.diyjourneys",
        "com.herofincorp.simplycash",
        "com.customer.herofincorp",
        "com.nextbillion.groww",
        "com.groww.app",
        "com.hdfcbank.payzapp",
        "com.snapmint.customerapp",
        "com.tataneu"
    )

    @Volatile
    private var blocked = false

    fun refresh(context: Context) {
        val configManager = ConfigManager(context)
        val config = configManager.load()
        val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
        val hookActive = config.hookedUpiApps.any { it.value } ||
            config.mockPhoneSim1.isNotBlank() ||
            configManager.readSpoofPhone().isNotBlank() ||
            mockOn ||
            config.autoHookForeground ||
            config.hookOutgoingSms ||
            config.interceptFakeSuccess
        val shouldBlock = (config.hookOutgoingSms || config.interceptFakeSuccess || mockOn) && hookActive

        if (shouldBlock && !blocked) {
            blockMessagingSend()
            blocked = true
            Log.i(TAG, "SEND_SMS blocked — verify SMS spoof active")
        } else if (!shouldBlock && blocked) {
            restoreMessagingSend()
            blocked = false
            Log.i(TAG, "SEND_SMS restored")
        }

        processBlockedFlag(context)
    }

    private fun blockMessagingSend() {
        val scopePkgs = readHookedScopePackages()
        val packages = listOf(
            "com.android.phone",
            "com.android.providers.telephony"
        ) + upiSmsSenders + scopePkgs
        packages.distinct().forEach { pkg ->
            ShellHelper.runSu("appops set $pkg SEND_SMS deny 2>/dev/null")
        }
    }

    private fun readHookedScopePackages(): List<String> {
        val paths = listOf(
            "/data/local/tmp/hivirtus_hooked_pkgs.txt",
            "/data/local/tmp/hivirtus_active_upi_all.txt",
            "/data/local/tmp/hivirtus_active_hook_pkg.txt"
        )
        val out = mutableListOf<String>()
        paths.forEach { path ->
            try {
                val f = File(path)
                if (!f.canRead()) return@forEach
                f.readLines().forEach { line ->
                    val pkg = line.trim()
                    if (pkg.isNotBlank() && pkg.contains('.')) out.add(pkg)
                }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun restoreMessagingSend() {
        val scopePkgs = readHookedScopePackages()
        val packages = listOf(
            "com.android.phone",
            "com.android.providers.telephony"
        ) + upiSmsSenders + scopePkgs
        packages.distinct().forEach { pkg ->
            ShellHelper.runSu("appops set $pkg SEND_SMS allow 2>/dev/null")
        }
    }

    private fun processBlockedFlag(context: Context) {
        val blocked = OutgoingBlockedReader.read() ?: return
        try {
            if (blocked.body.isBlank()) return
            OutgoingSmsCleaner.scrubSentIfNeeded(context, blocked.dest, blocked.body)
            VerifyTokenPipeline.handleBlocked(context, blocked.dest, blocked.body)
            OutgoingBlockedReader.deleteFlags()
        } catch (e: Exception) {
            Log.w(TAG, "Blocked flag process failed: ${e.message}")
        }
    }
}
