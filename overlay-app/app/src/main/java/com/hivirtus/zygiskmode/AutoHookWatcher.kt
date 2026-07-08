package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * Jo app foreground me kholo — automatically hook + phone/SMS mock (START HOOK ki zaroorat nahi).
 */
object AutoHookWatcher {

    private const val TAG = "AutoHookWatcher"

    private var lastForegroundPkg: String? = null
    private var lastHookAtMs = 0L

    fun tick(context: Context, configManager: ConfigManager) {
        val appContext = context.applicationContext
        val config = configManager.load()
        if (!config.autoHookForeground) return

        val fg = ForegroundAppHelper.foregroundPackage(appContext) ?: return
        if (!ActiveHookManager.isHookablePackage(appContext, fg)) return

        val active = ActiveHookManager.readActivePackage()
        if (fg == active && config.hookedUpiApps[fg] == true) {
            lastForegroundPkg = fg
            return
        }

        if (fg == lastForegroundPkg && System.currentTimeMillis() - lastHookAtMs < 2500L) {
            return
        }

        val result = ActiveHookManager.hookPackage(appContext, configManager, fg)
        if (result.success) {
            lastForegroundPkg = fg
            lastHookAtMs = System.currentTimeMillis()
            OutgoingSmsGuard.refresh(appContext)
            HookStatusBarManager(appContext).refresh()
            Log.i(TAG, "Auto-hooked ${result.displayName} ($fg)")
        }
    }

    fun reset() {
        lastForegroundPkg = null
        lastHookAtMs = 0L
        ActiveHookManager.clearRestartCache()
    }
}
