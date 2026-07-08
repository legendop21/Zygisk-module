package com.hivirtus.zygiskmode.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class HivirtusXposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.hivirtus.zygiskmode") return

        // Zygisk ZIP active hai to double-hook / crash avoid — sirf native module chalega.
        if (isZygiskNativeModuleActive()) {
            HookDebug.log(lpparam.packageName, "SKIP: Zygisk native module active")
            return
        }

        val config = runCatching { XposedConfigBridge.load(force = true) }
            .getOrElse { XposedConfigBridge.fallbackConfig() }

        if (!XposedConfigBridge.isTargetPackage(lpparam.packageName, config)) return

        markReady()

        try {
            StealthHook.install(lpparam)
            SimSpoofHook.install(lpparam, config)
            SmsSenderSpoofHook.install(lpparam, config)
            OutgoingSmsBlockHook.install(lpparam, config)
            UpiTimerHook.install(lpparam, config)
            HookDebug.log(lpparam.packageName, "ALL HOOKS LOADED ✓ phone=${XposedConfigBridge.readSpoofPhone(config)}")
        } catch (t: Throwable) {
            HookDebug.log(lpparam.packageName, "HOOK FAIL: ${t.message}")
        }
    }

    private fun isZygiskNativeModuleActive(): Boolean {
        val modDir = File("/data/adb/modules/hivirtus_zygisk_mode")
        if (!modDir.isDirectory) return false
        if (File(modDir, "disable").exists() || File(modDir, "remove").exists()) return false
        val hasZygiskLib = File(modDir, "zygisk/arm64-v8a.so").exists() ||
            File(modDir, "zygisk/armeabi-v7a.so").exists()
        if (!hasZygiskLib) return false
        return File("/data/local/tmp/hivirtus_zygisk_native.active").canRead() ||
            File("/data/local/tmp/hivirtus_module_installed.flag").canRead()
    }

    private fun markReady() {
        try {
            File("/data/local/tmp/hivirtus_lsposed_hook.ready").writeText("1")
            File("/data/local/tmp/hivirtus_module_installed.flag").writeText("1")
        } catch (_: Exception) {
        }
    }
}
