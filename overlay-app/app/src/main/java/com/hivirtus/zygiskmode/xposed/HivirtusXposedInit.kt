package com.hivirtus.zygiskmode.xposed

import com.hivirtus.zygiskmode.ModuleConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class HivirtusXposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.hivirtus.zygiskmode") return

        val config = XposedConfigBridge.load(force = true) ?: return
        if (!XposedConfigBridge.isTargetPackage(lpparam.packageName, config)) return

        markReady()

        try {
            StealthHook.install(lpparam)
            SimSpoofHook.install(lpparam, config)
            SmsSenderSpoofHook.install(lpparam, config)
            OutgoingSmsBlockHook.install(lpparam, config)
            UpiTimerHook.install(lpparam, config)
            HookDebug.log(lpparam.packageName, "ALL HOOKS LOADED ✓")
        } catch (t: Throwable) {
            HookDebug.log(lpparam.packageName, "HOOK FAIL: ${t.message}")
        }
    }

    private fun markReady() {
        try {
            File("/data/local/tmp/hivirtus_lsposed_hook.ready").writeText("1")
            File("/data/local/tmp/hivirtus_module_installed.flag").writeText("1")
        } catch (_: Exception) {
        }
    }
}
