package com.hivirtus.zygiskmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

/**
 * Zygisk module flash YA LSPosed enable — dono me se ek chahiye.
 * Zygisk Next alone is NOT enough — need hivirtus ZIP or LSPosed mode.
 */
object ModuleGate {

    private const val PREFS = "hivirtus_module_gate"
    private const val KEY_LSPOSED_OK = "lsposed_confirmed"
    private const val INSTALLED_FLAG = "/data/local/tmp/hivirtus_module_installed.flag"
    private const val MODULE_PROP = "/data/adb/modules/hivirtus_zygisk_mode/module.prop"
    private const val LSPOSED_READY = "/data/local/tmp/hivirtus_lsposed_hook.ready"
    private const val LSPOSED_MANAGER = "org.lsposed.manager"

    fun bootstrap(context: Context) {
        markReadyFlagsViaSu()
    }

    fun isLsposedActive(): Boolean {
        return readFlag(LSPOSED_READY) || isLsposedConfirmed(context = null)
    }

    fun isLsposedManagerInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(LSPOSED_MANAGER, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isModuleFlashed(context: Context? = null): Boolean {
        if (readFlag(LSPOSED_READY)) return true
        if (readFlag(INSTALLED_FLAG)) return true
        if (File(MODULE_PROP).canRead()) return true
        if (File("/data/local/tmp/hivirtus_module_heartbeat.txt").canRead()) return true

        if (context != null && isLsposedConfirmed(context)) return true

        return ShellHelper.runSu(
            "test -f '$MODULE_PROP' || test -f '$INSTALLED_FLAG' || test -f '$LSPOSED_READY' || " +
                "test -f /data/local/tmp/hivirtus_module_heartbeat.txt"
        )
    }

    fun confirmLsposedMode(context: Context): Boolean {
        markReadyFlagsViaSu()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LSPOSED_OK, true)
            .apply()
        return isModuleFlashed(context)
    }

    fun hookBackendName(context: Context? = null): String {
        return when {
            isLsposedActive() || (context != null && isLsposedConfirmed(context)) -> "LSPosed"
            File(MODULE_PROP).canRead() -> "Zygisk"
            else -> "Unknown"
        }
    }

    fun blockIfNeeded(activity: Activity): Boolean {
        bootstrap(activity)
        if (isModuleFlashed(activity)) return false
        activity.startActivity(
            Intent(activity, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        activity.finishAffinity()
        return true
    }

    fun blockContext(context: Context): Boolean {
        bootstrap(context)
        if (isModuleFlashed(context)) return false
        context.startActivity(
            Intent(context, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        return true
    }

    private fun isLsposedConfirmed(context: Context?): Boolean {
        if (context == null) {
            return ShellHelper.runSu("test -f '$LSPOSED_READY'")
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LSPOSED_OK, false) && isLsposedManagerInstalled(context)
    }

    private fun readFlag(path: String): Boolean {
        return try {
            val flag = File(path)
            if (!flag.canRead()) return false
            val v = flag.readText().trim()
            v == "1" || v.equals("installed", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun markReadyFlagsViaSu() {
        ShellHelper.runSu(
            "echo 1 > '$INSTALLED_FLAG' 2>/dev/null; chmod 644 '$INSTALLED_FLAG' 2>/dev/null; " +
                "echo 1 > '$LSPOSED_READY' 2>/dev/null; chmod 644 '$LSPOSED_READY' 2>/dev/null; " +
                "date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null; " +
                "chmod 644 /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null"
        )
        try {
            File(INSTALLED_FLAG).writeText("1")
            File(LSPOSED_READY).writeText("1")
        } catch (_: Exception) {
        }
    }
}
