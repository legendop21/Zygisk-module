package com.hivirtus.zygiskmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Zygisk module flash YA LSPosed enable — dono me se ek chahiye.
 */
object ModuleGate {

    private const val INSTALLED_FLAG = "/data/local/tmp/hivirtus_module_installed.flag"
    private const val MODULE_PROP = "/data/adb/modules/hivirtus_zygisk_mode/module.prop"
    private const val LSPOSED_READY = "/data/local/tmp/hivirtus_lsposed_hook.ready"

    fun isLsposedActive(): Boolean {
        return try {
            File(LSPOSED_READY).canRead()
        } catch (_: Exception) {
            false
        }
    }

    fun isModuleFlashed(): Boolean {
        if (isLsposedActive()) return true

        try {
            val flag = File(INSTALLED_FLAG)
            if (flag.canRead()) {
                val v = flag.readText().trim()
                if (v == "1" || v.equals("installed", ignoreCase = true)) return true
            }
        } catch (_: Exception) {}

        if (File(MODULE_PROP).canRead()) return true

        return ShellHelper.runSu(
            "test -f '$MODULE_PROP' || test -f '$INSTALLED_FLAG' || test -f '$LSPOSED_READY'"
        )
    }

    fun hookBackendName(): String =
        if (isLsposedActive()) "LSPosed" else "Zygisk"

    fun blockIfNeeded(activity: Activity): Boolean {
        if (isModuleFlashed()) return false
        activity.startActivity(
            Intent(activity, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        activity.finishAffinity()
        return true
    }

    fun blockContext(context: Context): Boolean {
        if (isModuleFlashed()) return false
        context.startActivity(
            Intent(context, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        return true
    }
}
