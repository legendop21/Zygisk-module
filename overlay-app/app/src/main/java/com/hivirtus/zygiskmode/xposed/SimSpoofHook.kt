package com.hivirtus.zygiskmode.xposed

import android.telephony.TelephonyManager
import com.hivirtus.zygiskmode.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SimSpoofHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!config.enablePhoneSpoof && !config.enableSim1Mock && !config.enableSim2Mock) return

        val phone = XposedConfigBridge.readSpoofPhone(config)
        if (phone.isBlank()) return

        val iso = config.mockCountryIso.ifBlank { "in" }

        hookTelephony(lpparam, phone, iso)
        hookSubscription(lpparam, phone, iso)
        hookSystemProperties(lpparam, phone, iso)
        HookDebug.logHook(lpparam.packageName, "SimSpoof phone=$phone")
    }

    private fun hookTelephony(lpparam: XC_LoadPackage.LoadPackageParam, phone: String, iso: String) {
        val tm = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)

        listOf("getLine1Number", "getMsisdn", "getVoiceMailNumber").forEach { method ->
            XposedBridge.hookAllMethods(tm, method, afterString(phone))
        }

        XposedBridge.hookAllMethods(tm, "getSimCountryIso", afterString(iso))
        XposedBridge.hookAllMethods(tm, "getNetworkCountryIso", afterString(iso))
        XposedBridge.hookAllMethods(tm, "getSimState", afterInt(TelephonyManager.SIM_STATE_READY))
        XposedBridge.hookAllMethods(tm, "getSimOperator", afterString("40445"))
        XposedBridge.hookAllMethods(tm, "getSimOperatorName", afterString("IND-JIO"))
    }

    private fun hookSubscription(lpparam: XC_LoadPackage.LoadPackageParam, phone: String, iso: String) {
        runCatching {
            val info = XposedHelpers.findClass("android.telephony.SubscriptionInfo", lpparam.classLoader)
            XposedBridge.hookAllMethods(info, "getNumber", afterString(phone))
            XposedBridge.hookAllMethods(info, "getCountryIso", afterString(iso))
            XposedBridge.hookAllMethods(info, "getMccString", afterString("404"))
            XposedBridge.hookAllMethods(info, "getMncString", afterString("45"))
        }
    }

    private fun hookSystemProperties(lpparam: XC_LoadPackage.LoadPackageParam, phone: String, iso: String) {
        runCatching {
            val sp = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
            XposedBridge.hookAllMethods(sp, "get", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    val lower = key.lowercase()
                    when {
                        lower.contains("line1") || lower.contains("msisdn") ||
                            lower.contains("phone_number") || lower.contains("simnum") ->
                            param.result = phone
                        lower.contains("country") && lower.contains("iso") ->
                            param.result = iso
                        lower.contains("operator.numeric") ->
                            param.result = "40445"
                        lower.contains("operator.alpha") ->
                            param.result = "JIO"
                    }
                }
            })
        }
    }

    private fun afterString(value: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.throwable == null) param.result = value
        }
    }

    private fun afterInt(value: Int) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.throwable == null) param.result = value
        }
    }
}
