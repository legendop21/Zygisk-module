package com.hivirtus.zygiskmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Zygisk-only gate — LSPosed / Xposed not used.
 * Virtus APK = menu + config; hooks = native Zygisk module.
 */
object ModuleGate {

    private const val MODULE_PROP = "/data/adb/modules/hivirtus_zygisk_mode/module.prop"
    private const val MODULE_FLAG = "/data/local/tmp/hivirtus_module_installed.flag"
    private const val ZYGISK_NATIVE = "/data/local/tmp/hivirtus_zygisk_native.active"
    private const val HEARTBEAT = "/data/local/tmp/hivirtus_module_heartbeat.txt"

    fun bootstrap(context: Context) {
        markReadyFlagsQuietly()
    }

    fun isModuleFlashed(context: Context? = null): Boolean {
        if (File(MODULE_PROP).canRead()) return true
        if (File(MODULE_FLAG).canRead()) return true
        context?.let {
            val cfg = File(it.filesDir, ConfigManager.APP_CONFIG_NAME)
            if (cfg.canRead() && cfg.length() > 32) return true
        }
        return false
    }

    fun isZygiskLoaded(): Boolean =
        File(ZYGISK_NATIVE).canRead() ||
            File(HEARTBEAT).canRead() ||
            File("/data/local/tmp/hivirtus_zygisk_mode.log").canRead()

    fun hookBackendName(): String = "Zygisk"

    fun blockIfNeeded(activity: Activity): Boolean {
        if (isModuleFlashed(activity)) return false
        activity.startActivity(
            Intent(activity, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        activity.finish()
        return true
    }

    fun blockContext(context: Context): Boolean {
        if (isModuleFlashed(context)) return false
        context.startActivity(
            Intent(context, ModuleBlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun markReadyFlagsQuietly() {
        ShellHelper.runSu(
            "echo 1 > /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null; " +
                "date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null"
        )
    }
}
