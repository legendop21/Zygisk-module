package com.hivirtus.zygiskmode.xposed

import android.app.PendingIntent
import com.hivirtus.zygiskmode.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object OutgoingSmsBlockHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!config.interceptFakeSuccess && !config.hookOutgoingSms) return
        if (!XposedConfigBridge.anyHooked(config)) return

        val smsManager = XposedHelpers.findClass("android.telephony.SmsManager", lpparam.classLoader)
        val blockHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val configNow = XposedConfigBridge.load() ?: return
                val dest = extractDest(param) ?: return
                val body = extractBody(param) ?: return
                if (!XposedConfigBridge.shouldBlockOutgoing(configNow, dest, body)) return

                HookDebug.log(lpparam.packageName, "BLOCK SMS → $dest | ${body.take(40)}")

                writeBlockedFlag(dest, body)
                fireSentIntent(param.args)
                param.setResult(null)
            }
        }

        listOf(
            "sendTextMessage",
            "sendTextMessageWithoutPersisting",
            "sendMultipartTextMessage",
            "sendDataMessage"
        ).forEach { method ->
            runCatching {
                XposedBridge.hookAllMethods(smsManager, method, blockHook)
            }
        }

        hookInstrumentation(lpparam, config)
        HookDebug.logHook(lpparam.packageName, "OutgoingSmsBlock")
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (lpparam.packageName != "android") return
        runCatching {
            val inst = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            XposedBridge.hookAllMethods(inst, "execStartActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.find { it is android.content.Intent } as? android.content.Intent
                        ?: return
                    val data = intent.data?.toString().orEmpty().lowercase()
                    if (!data.startsWith("sms:") && !data.startsWith("smsto:")) return
                    val configNow = XposedConfigBridge.load() ?: return
                    if (!XposedConfigBridge.anyHooked(configNow)) return
                    HookDebug.log("framework", "BLOCK sms intent $data")
                    param.result = 0
                }
            })
        }
    }

    private fun extractDest(param: XC_MethodHook.MethodHookParam): String? {
        return param.args.firstOrNull { it is String && (it as String).isNotBlank() } as? String
    }

    private fun extractBody(param: XC_MethodHook.MethodHookParam): String? {
        val strings = param.args.filterIsInstance<String>()
        return strings.getOrNull(2) ?: strings.lastOrNull { it.length > 3 }
    }

    private fun fireSentIntent(args: Array<Any?>) {
        args.filterIsInstance<PendingIntent>().forEach { pi ->
            runCatching { pi.send() }
        }
    }

    private fun writeBlockedFlag(dest: String, body: String) {
        try {
            File("/data/local/tmp/hivirtus_outgoing_blocked.flag")
                .writeText("$dest|$body")
            File("/data/local/tmp/hivirtus_outgoing_fake_ok.flag").writeText("ok")
        } catch (_: Exception) {
        }
    }
}
