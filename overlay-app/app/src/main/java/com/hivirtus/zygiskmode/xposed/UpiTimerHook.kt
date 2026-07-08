package com.hivirtus.zygiskmode.xposed

import com.hivirtus.zygiskmode.ModuleConfig
import com.hivirtus.zygiskmode.SmsMatcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object UpiTimerHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!XposedConfigBridge.isUpiApp(lpparam.packageName, config)) return
        if (!config.hookUpiVerification) return

        val bonusMs = (config.upiAppTimerBonuses[lpparam.packageName] ?: config.upiTimerBonusSeconds) * 1000L
        if (bonusMs <= 0L) return

        XposedBridge.hookAllMethods(
            XposedHelpers.findClass("java.lang.System", lpparam.classLoader),
            "currentTimeMillis",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val now = param.result as? Long ?: return
                    param.result = now - bonusMs
                }
            }
        )
        HookDebug.logHook(lpparam.packageName, "UpiTimer -${bonusMs}ms")
    }
}
