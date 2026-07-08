package com.hivirtus.zygiskmode.xposed

import com.hivirtus.zygiskmode.ModuleConfig
import com.hivirtus.zygiskmode.SmsMatcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SmsSenderSpoofHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!config.overrideIncomingSender) return
        val senderId = SmsMatcher.userSenderId(config) ?: return

        val sms = XposedHelpers.findClass("android.telephony.SmsMessage", lpparam.classLoader)
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val actual = param.result as? String ?: return
                if (actual.equals(senderId, ignoreCase = true)) return
                param.result = senderId
            }
        }

        XposedBridge.hookAllMethods(sms, "getOriginatingAddress", hook)
        XposedBridge.hookAllMethods(sms, "getDisplayOriginatingAddress", hook)
        HookDebug.logHook(lpparam.packageName, "SenderSpoof → $senderId")
    }
}
