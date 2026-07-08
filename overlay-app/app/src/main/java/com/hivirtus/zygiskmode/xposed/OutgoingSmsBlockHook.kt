package com.hivirtus.zygiskmode.xposed

import android.app.PendingIntent
import com.hivirtus.zygiskmode.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object OutgoingSmsBlockHook {

  private val sendMethodNames = listOf(
      "sendTextMessage",
      "sendTextMessageWithoutPersisting",
      "sendMultipartTextMessage",
      "sendDataMessage",
      "sendText",
      "sendTextForSubscriber",
      "sendMultipartText",
      "sendRawPdu",
      "sendNewSubmitPdu"
  )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, config: ModuleConfig) {
        if (!config.interceptFakeSuccess && !config.hookOutgoingSms) return

        val blockHook = createBlockHook(lpparam.packageName)

        runCatching {
            val smsManager = XposedHelpers.findClass("android.telephony.SmsManager", lpparam.classLoader)
            sendMethodNames.forEach { method ->
                runCatching { XposedBridge.hookAllMethods(smsManager, method, blockHook) }
            }
        }

        if (lpparam.packageName == "com.android.phone") {
            hookPhoneSmsClasses(lpparam, blockHook)
        }

        if (lpparam.packageName in XposedConfigBridge.systemPackages) {
            hookInstrumentation(lpparam)
        }

        HookDebug.logHook(lpparam.packageName, "OutgoingSmsBlock HARD")
    }

    private fun hookPhoneSmsClasses(lpparam: XC_LoadPackage.LoadPackageParam, blockHook: XC_MethodHook) {
        val classes = listOf(
            "com.android.internal.telephony.SmsController",
            "com.android.internal.telephony.IccSmsInterfaceManager",
            "com.android.internal.telephony.SmsDispatchersController",
            "com.android.internal.telephony.SmsSender",
            "com.android.internal.telephony.RIL",
            "com.android.internal.telephony.SmsUsageMonitor",
            "com.android.internal.telephony.ImsSmsDispatcher",
            "com.android.internal.telephony.GsmSmsDispatcher",
            "com.android.internal.telephony.CdmaSmsDispatcher"
        )
        classes.forEach { name ->
            runCatching {
                val clazz = XposedHelpers.findClass(name, lpparam.classLoader)
                sendMethodNames.forEach { method ->
                    runCatching { XposedBridge.hookAllMethods(clazz, method, blockHook) }
                }
                listOf("sendSms", "sendSMS", "sendImsSms", "injectSmsPdu").forEach { method ->
                    runCatching { XposedBridge.hookAllMethods(clazz, method, blockHook) }
                }
            }
        }
    }

    private fun createBlockHook(pkg: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val configNow = XposedConfigBridge.load()
            val dest = extractDest(param) ?: ""
            val body = extractBody(param) ?: ""
            if (!XposedConfigBridge.shouldBlockOutgoing(configNow, dest, body)) return

            HookDebug.log(pkg, "BLOCK SMS → $dest | ${body.take(60)}")

            if (dest.isNotBlank() && body.isNotBlank()) {
                writeBlockedFlag(dest, body)
            }
            fireSentIntent(param.args)
            param.setResult(null)
        }
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        runCatching {
            val inst = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            XposedBridge.hookAllMethods(inst, "execStartActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.find { it is android.content.Intent } as? android.content.Intent
                        ?: return
                    val data = intent.data?.toString().orEmpty().lowercase()
                    if (!data.startsWith("sms:") && !data.startsWith("smsto:")) return
                    HookDebug.log("framework", "BLOCK sms intent $data")
                    param.result = 0
                }
            })
        }
    }

    private fun extractDest(param: XC_MethodHook.MethodHookParam): String? {
        param.args.forEach { arg ->
            if (arg is String && arg.isNotBlank()) {
                val digits = arg.replace(Regex("[^0-9]"), "")
                if (digits.length in 4..15) return arg
            }
        }
        return param.args.firstOrNull { it is String && (it as String).isNotBlank() } as? String
    }

    private fun extractBody(param: XC_MethodHook.MethodHookParam): String? {
        val strings = param.args.filterIsInstance<String>()
        return strings.firstOrNull { it.length > 3 && !it.matches(Regex("^[+0-9\\s-]{4,15}$")) }
            ?: strings.getOrNull(2)
            ?: strings.lastOrNull { it.length > 2 }
    }

    private fun fireSentIntent(args: Array<Any?>) {
        args.filterIsInstance<PendingIntent>().forEach { pi ->
            runCatching { pi.send() }
        }
    }

    private fun writeBlockedFlag(dest: String, body: String) {
        try {
            File("/data/local/tmp/hivirtus_outgoing_blocked.flag").writeText("$dest|$body")
            File("/data/local/tmp/hivirtus_outgoing_fake_ok.flag").writeText("ok")
        } catch (_: Exception) {
        }
    }
}
