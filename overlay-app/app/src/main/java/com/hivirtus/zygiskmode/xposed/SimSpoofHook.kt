package com.hivirtus.zygiskmode.xposed

import android.telephony.TelephonyManager
import com.hivirtus.zygiskmode.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SimSpoofHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!XposedConfigBridge.shouldSimSpoof(config)) return

        val phone = XposedConfigBridge.readSpoofPhone(config)
        if (phone.isBlank()) return

        val iso = config.mockCountryIso.ifBlank { "in" }
        val digits10 = phone.replace(Regex("[^0-9]"), "").takeLast(10)

        hookTelephony(lpparam, phone, iso, digits10)
        hookSubscription(lpparam, phone, iso)
        hookSubscriptionManager(lpparam, phone)
        hookSystemProperties(lpparam, phone, iso)
        hookPhoneProcess(lpparam, phone)
        HookDebug.logHook(lpparam.packageName, "SimSpoof phone=$phone")
    }

    private fun hookTelephony(
        lpparam: XC_LoadPackage.LoadPackageParam,
        phone: String,
        iso: String,
        digits10: String
    ) {
        val tm = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)

        listOf(
            "getLine1Number", "getMsisdn", "getVoiceMailNumber",
            "getLine1NumberForDisplay", "getNai"
        ).forEach { method ->
            runCatching { XposedBridge.hookAllMethods(tm, method, afterString(phone)) }
        }
        listOf(
            "getSubscriberId", "getSimSerialNumber", "getImei", "getMeid", "getDeviceId"
        ).forEach { method ->
            runCatching {
                XposedBridge.hookAllMethods(
                    tm,
                    method,
                    afterString(digits10.ifBlank { phone.replace(Regex("[^0-9]"), "") })
                )
            }
        }

        XposedBridge.hookAllMethods(tm, "getSimCountryIso", afterString(iso))
        XposedBridge.hookAllMethods(tm, "getNetworkCountryIso", afterString(iso))
        XposedBridge.hookAllMethods(tm, "getSimState", afterInt(TelephonyManager.SIM_STATE_READY))
        XposedBridge.hookAllMethods(tm, "getSimOperator", afterString("40445"))
        XposedBridge.hookAllMethods(tm, "getSimOperatorName", afterString("IND-JIO"))
        XposedBridge.hookAllMethods(tm, "getPhoneCount", afterInt(1))
        XposedBridge.hookAllMethods(tm, "getActiveModemCount", afterInt(1))
    }

    private fun hookSubscription(lpparam: XC_LoadPackage.LoadPackageParam, phone: String, iso: String) {
        runCatching {
            val info = XposedHelpers.findClass("android.telephony.SubscriptionInfo", lpparam.classLoader)
            XposedBridge.hookAllMethods(info, "getNumber", afterString(phone))
            XposedBridge.hookAllMethods(info, "getCountryIso", afterString(iso))
            XposedBridge.hookAllMethods(info, "getMccString", afterString("404"))
            XposedBridge.hookAllMethods(info, "getMncString", afterString("45"))
            XposedBridge.hookAllMethods(info, "getCarrierName", afterString("JIO 4G"))
            XposedBridge.hookAllMethods(info, "getDisplayName", afterString("JIO 4G"))
        }
    }

    private fun hookSubscriptionManager(lpparam: XC_LoadPackage.LoadPackageParam, phone: String) {
        runCatching {
            val sm = XposedHelpers.findClass("android.telephony.SubscriptionManager", lpparam.classLoader)
            XposedBridge.hookAllMethods(sm, "getPhoneNumber", afterString(phone))
            XposedBridge.hookAllMethods(sm, "getPhoneNumberForSubscriber", afterString(phone))
        }
    }

    private fun hookPhoneProcess(lpparam: XC_LoadPackage.LoadPackageParam, phone: String) {
        if (lpparam.packageName != "com.android.phone") return
        val targets = listOf(
            "com.android.internal.telephony.PhoneSubInfoController",
            "com.android.internal.telephony.SmsController",
            "com.android.internal.telephony.IccSmsInterfaceManager",
            "com.android.phone.PhoneInterfaceManager"
        )
        targets.forEach { cls ->
            runCatching {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                XposedBridge.hookAllMethods(clazz, "getLine1Number", afterString(phone))
                XposedBridge.hookAllMethods(clazz, "getLine1NumberForSubscriber", afterString(phone))
                XposedBridge.hookAllMethods(clazz, "getMsisdnForSubscriber", afterString(phone))
            }
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
                            lower.contains("phone_number") || lower.contains("simnum") ||
                            lower.contains("nitz_number") ->
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
