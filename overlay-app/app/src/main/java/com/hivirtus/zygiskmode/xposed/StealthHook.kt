package com.hivirtus.zygiskmode.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StealthHook {

    private val hiddenPackages = setOf(
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "com.hivirtus.zygiskmode"
    )

    private val hiddenPaths = listOf(
        "xposed", "lsposed", "magisk", "kernelsu", "apatch", "zygisk",
        "hivirtus", "/data/adb/", "supersu", "busybox"
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        scrubStackTrace(lpparam)
        hidePackages(lpparam)
        hideFiles(lpparam)
        spoofDebugProps(lpparam)
        HookDebug.logHook(lpparam.packageName, "Stealth")
    }

    private fun scrubStackTrace(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("java.lang.Throwable", lpparam.classLoader),
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val trace = param.result as? Array<StackTraceElement> ?: return
                        @Suppress("UNCHECKED_CAST")
                        param.result = trace.filter { el ->
                            val name = el.className.lowercase()
                            hiddenPaths.none { name.contains(it) }
                        }.toTypedArray()
                    }
                }
            )
        }
    }

    private fun hidePackages(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val pm = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(pm, "getPackageInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    if (pkg in hiddenPackages) {
                        param.setThrowable(android.content.pm.PackageManager.NameNotFoundException(pkg))
                    }
                }
            })
            XposedBridge.hookAllMethods(pm, "getApplicationInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    if (pkg in hiddenPackages) {
                        param.setThrowable(android.content.pm.PackageManager.NameNotFoundException(pkg))
                    }
                }
            })
        }
    }

    private fun hideFiles(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("java.io.File", lpparam.classLoader),
                "exists",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = (param.thisObject as java.io.File).absolutePath.lowercase()
                        if (hiddenPaths.any { path.contains(it) }) {
                            param.result = false
                        }
                    }
                }
            )
        }
    }

    private fun spoofDebugProps(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val sp = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
            XposedBridge.hookAllMethods(sp, "get", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    when (key) {
                        "ro.debuggable" -> param.result = "0"
                        "ro.secure" -> param.result = "1"
                        "ro.build.tags" -> param.result = "release-keys"
                        "ro.build.type" -> param.result = "user"
                    }
                }
            })
        }
    }
}
