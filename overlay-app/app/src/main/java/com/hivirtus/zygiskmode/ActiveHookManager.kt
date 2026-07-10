package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File

object ActiveHookManager {

    private const val ACTIVE_FILE = "/data/local/tmp/hivirtus_active_upi.txt"
    private const val ACTIVE_PKG_FILE = "/data/local/tmp/hivirtus_active_hook_pkg.txt"

    private val restartedForHook = mutableSetOf<String>()

    data class HookResult(
        val success: Boolean,
        val packageName: String,
        val displayName: String,
        val message: String
    )

    fun isHookablePackage(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == context.packageName) return false
        val lower = pkg.lowercase()
        if (lower.contains("launcher")) return false
        if (lower.contains("systemui")) return false
        if (lower.contains("inputmethod")) return false
        if (lower == "com.android.settings") return false
        if (lower.startsWith("com.android.systemui")) return false
        if (lower == "com.google.android.apps.messaging") return false
        if (lower == "com.android.mms") return false
        if (lower == "com.android.phone") return false
        return true
    }

    fun hookForegroundApp(context: Context, configManager: ConfigManager): HookResult {
        val pkg = ForegroundAppHelper.foregroundPackage(context)
            ?: return HookResult(false, "", "", "Koi app foreground me nahi — pehle UPI app kholo")

        if (!isHookablePackage(context, pkg)) {
            return HookResult(false, pkg, "", "Pehle UPI/loan app kholo")
        }

        return HookEngine.applyHook(context, configManager, pkg, mergeSelection = true)
    }

    fun hookPackage(context: Context, configManager: ConfigManager, pkg: String): HookResult {
        return HookEngine.applyHook(context, configManager, pkg, mergeSelection = true)
    }

    fun forceStop(pkg: String) {
        ShellHelper.runSu("am force-stop $pkg 2>/dev/null")
    }

    fun forceStopOnce(pkg: String) {
        if (restartedForHook.add(pkg)) {
            forceStop(pkg)
        }
    }

    fun forceStopAll(packages: Collection<String>) {
        packages.filter { it.isNotBlank() }.distinct().forEach { forceStop(it) }
    }

    fun clearRestartCache() {
        restartedForHook.clear()
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
        val config = ConfigManager(context).load()
        val selected = config.hookedUpiApps.filter { it.value }
        if (selected.size > 1) {
            return context.getString(R.string.active_hook_multi, selected.size)
        }
        val pkg = readActivePackage() ?: selected.keys.firstOrNull()
        if (pkg.isNullOrBlank()) return "None"
        return UpiAppRegistry.displayNameFor(pkg)
    }

    fun selectedHookCount(context: Context): Int {
        return ConfigManager(context).load().hookedUpiApps.count { it.value }
    }

    fun isSelectedHooked(config: ModuleConfig, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (config.hookedUpiApps[pkg] == true) return true
        return readActivePackage() == pkg
    }

    fun persistAllSelected(packages: Collection<String>) {
        val list = packages.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        val primary = list.last()
        persistActivePackage(primary, UpiAppRegistry.displayNameFor(primary))
        val payload = list.joinToString("\n")
        try {
            java.io.File("/data/local/tmp/hivirtus_active_upi_all.txt").writeText(payload)
        } catch (_: Exception) {
            ShellHelper.runSu(
                "printf '%s' '$payload' > /data/local/tmp/hivirtus_active_upi_all.txt && " +
                    "chmod 644 /data/local/tmp/hivirtus_active_upi_all.txt"
            )
        }
    }

    fun persistHookedScope(packages: Collection<String>) {
        val list = packages.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        val payload = list.joinToString("\n")
        try {
            File("/data/local/tmp/hivirtus_hooked_pkgs.txt").writeText(payload)
        } catch (_: Exception) {
            ShellHelper.runSu(
                "printf '%s' '$payload' > /data/local/tmp/hivirtus_hooked_pkgs.txt && " +
                    "chmod 644 /data/local/tmp/hivirtus_hooked_pkgs.txt"
            )
        }
    }

    fun persistActivePackage(pkg: String, display: String) {
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
