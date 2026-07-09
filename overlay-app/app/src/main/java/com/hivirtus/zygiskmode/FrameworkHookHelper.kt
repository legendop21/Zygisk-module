package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * Zygisk scope: UPI tab me jo select karo → sirf woh app hook (native module).
 */
object FrameworkHookHelper {

    private const val TAG = "FrameworkHookHelper"
    private const val SCOPE_FLAG = "/data/local/tmp/hivirtus_framework_scope_active.flag"

    fun selectedPackages(config: ModuleConfig): List<String> =
        config.hookedUpiApps.filter { it.value }.keys.toList()

    fun hasSelectedApps(config: ModuleConfig): Boolean =
        selectedPackages(config).isNotEmpty()

    fun activateScope(context: Context, configManager: ConfigManager, selection: Map<String, Boolean>) {
        val selected = selection.filter { it.value }.keys
        if (selected.isEmpty()) return

        markScopeActive()
        val hookedCount = HookEngine.applyAllSelectedHooks(context, configManager, selection)
        refreshStacks(selected)
        OutgoingSmsGuard.refresh(context.applicationContext)
        Log.i(TAG, "Framework scope active for $hookedCount apps: ${selected.joinToString()}")
    }

    fun refreshStacks(selected: Set<String> = emptySet()) {
        TelephonyInjectHelper.wakeTelephonyPipeline()
        SmsStackRefresher.refreshAfterSenderIdChange()
        selected.forEach { pkg ->
            ShellHelper.runSu("am force-stop $pkg 2>/dev/null")
        }
    }

    fun markScopeActivePublic() {
        markScopeActive()
    }

    private fun markScopeActive() {
        try {
            java.io.File(SCOPE_FLAG).writeText("1")
        } catch (_: Exception) {
            ShellHelper.runSu("echo 1 > '$SCOPE_FLAG' && chmod 644 '$SCOPE_FLAG'")
        }
    }
}
