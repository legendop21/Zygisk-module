package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File

object ActiveHookManager {

    private const val ACTIVE_FILE = "/data/local/tmp/hivirtus_active_upi.txt"
    private const val ACTIVE_PKG_FILE = "/data/local/tmp/hivirtus_active_hook_pkg.txt"

    data class HookResult(
        val success: Boolean,
        val packageName: String,
        val displayName: String,
        val message: String
    )

    fun hookForegroundApp(context: Context, configManager: ConfigManager): HookResult {
        val pkg = ForegroundAppHelper.foregroundPackage(context)
            ?: return HookResult(false, "", "", "Koi app foreground me nahi — pehle KreditBee/FamPay kholo")

        if (pkg == context.packageName || pkg.contains("systemui") || pkg.contains("launcher")) {
            return HookResult(false, pkg, "", "Pehle UPI/loan app kholo, phir Start Hook dabao")
        }

        val display = UpiAppRegistry.displayNameFor(pkg)
        val hooked = UpiAppRegistry.defaultHookMap().mapValues { it.key == pkg }.toMutableMap()
        hooked[pkg] = true

        val current = configManager.load()
        val phone = current.mockPhoneSim1.ifBlank { "+919876543210" }
        val saved = configManager.save(
            current.copy(
                hookedUpiApps = hooked,
                hookUpiVerification = true,
                hookIncomingSms = true,
                hookOutgoingSms = true,
                interceptFakeSuccess = true,
                autoExtractOtp = true,
                overrideIncomingSender = current.overrideIncomingSender,
                enablePhoneSpoof = current.enablePhoneSpoof || current.enableSim1Mock,
                enableSim1Mock = current.enableSim1Mock || current.enablePhoneSpoof,
                mockPhoneSim1 = phone
            )
        )
        if (!saved) {
            return HookResult(false, pkg, display, "Config save fail — dubara try karo")
        }

        if (current.enablePhoneSpoof || current.enableSim1Mock) {
            configManager.writeSpoofPhone(phone)
        }
        persistActivePackage(pkg, display)
        return HookResult(true, pkg, display, "$display hooked ✅")
    }

    fun readActivePackage(): String? {
        return try {
            File(ACTIVE_PKG_FILE).takeIf { it.canRead() }?.readText()?.trim()?.ifBlank { null }
                ?: File(ACTIVE_FILE).takeIf { it.canRead() }?.readLines()?.lastOrNull { it.isNotBlank() }?.trim()
        } catch (_: Exception) {
            null
        }
    }

    fun readActiveDisplayName(context: Context): String {
        val pkg = readActivePackage() ?: return "None"
        return UpiAppRegistry.displayNameFor(pkg)
    }

    private fun persistActivePackage(pkg: String, display: String) {
        try {
            File(ACTIVE_PKG_FILE).writeText(pkg)
        } catch (_: Exception) {
            ShellHelper.runSu("echo '$pkg' > '$ACTIVE_PKG_FILE' && chmod 644 '$ACTIVE_PKG_FILE'")
        }
        try {
            File(ACTIVE_FILE).appendText("$pkg\n")
        } catch (_: Exception) {
            ShellHelper.runSu("echo '$pkg' >> '$ACTIVE_FILE' && chmod 644 '$ACTIVE_FILE'")
        }
        try {
            File("/data/local/tmp/hivirtus_active_hook_label.txt").writeText(display)
        } catch (_: Exception) {}
    }
}
