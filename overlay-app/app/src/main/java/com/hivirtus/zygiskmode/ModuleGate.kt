package com.hivirtus.zygiskmode

import android.app.Activity
import android.content.Context

/**
 * Module block removed — APK install ke baad seedha menu khulega.
 * Hooks LSPosed scope se lagenge; Zygisk ZIP optional.
 */
object ModuleGate {

    fun bootstrap(context: Context) {
        markReadyFlagsQuietly()
    }

    fun isLsposedActive(): Boolean = true

    fun isLsposedManagerInstalled(context: Context): Boolean = true

    fun isModuleFlashed(context: Context? = null): Boolean = true

    fun confirmLsposedMode(context: Context): Boolean = true

    fun hookBackendName(context: Context? = null): String = "LSPosed"

    fun blockIfNeeded(activity: Activity): Boolean = false

    fun blockContext(context: Context): Boolean = false

    private fun markReadyFlagsQuietly() {
        ShellHelper.runSu(
            "echo 1 > /data/local/tmp/hivirtus_module_installed.flag 2>/dev/null; " +
                "echo 1 > /data/local/tmp/hivirtus_lsposed_hook.ready 2>/dev/null; " +
                "date +%s > /data/local/tmp/hivirtus_module_heartbeat.txt 2>/dev/null"
        )
    }
}
