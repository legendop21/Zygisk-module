package com.hivirtus.zygiskmode

import android.content.Context
import android.util.Log

/**
 * START HOOK / Auto Hook — config sync, phone spoof, telephony wake, app restart.
 */
object HookEngine {

    private const val TAG = "HookEngine"

    fun applyHook(
        context: Context,
        configManager: ConfigManager,
        targetPkg: String,
        mergeSelection: Boolean = true
    ): ActiveHookManager.HookResult {
        if (!ActiveHookManager.isHookablePackage(context, targetPkg)) {
            return ActiveHookManager.HookResult(
                false, targetPkg, "",
                "Ye app hook nahi ho sakti — UPI/loan app kholo"
            )
        }

        val display = UpiAppRegistry.displayNameFor(targetPkg)
        val current = configManager.load()
        val phone = configManager.readSpoofPhone().ifBlank { current.mockPhoneSim1 }
        val mockOn = current.enableVirtualSim || current.enableSim1Mock || current.enablePhoneSpoof

        val hooked = if (mergeSelection) {
            current.hookedUpiApps.toMutableMap()
        } else {
            UpiAppRegistry.defaultHookMap().toMutableMap()
        }
        hooked[targetPkg] = true

        val senderId = current.injectSenderId.trim()
        val hasSenderId = senderId.isNotBlank() && !senderId.equals("AD-TEST-S", ignoreCase = true)

        val updated = current.copy(
            hookedUpiApps = hooked,
            hookUpiVerification = true,
            hookIncomingSms = current.hookIncomingSms,
            hookOutgoingSms = current.hookOutgoingSms,
            interceptFakeSuccess = current.interceptFakeSuccess,
            prefixEnabled = current.prefixEnabled,
            prefixText = current.prefixText,
            autoExtractOtp = true,
            autoHookForeground = true,
            overrideIncomingSender = hasSenderId || current.overrideIncomingSender,
            enablePhoneSpoof = mockOn,
            enableSim1Mock = mockOn,
            enableVirtualSim = mockOn,
            mockPhoneSim1 = phone
        )

        if (!configManager.saveAndFlushSync(updated)) {
            return ActiveHookManager.HookResult(false, targetPkg, display, "Config save fail")
        }

        if (mockOn) configManager.writeSpoofPhoneSync(phone)
        TelephonyInjectHelper.wakeTelephonyPipeline()
        SmsStackRefresher.refreshAfterSenderIdChange()
        ActiveHookManager.forceStopOnce(targetPkg)
        ActiveHookManager.persistActivePackage(targetPkg, display)
        ActiveHookManager.persistHookedScope(hooked.filter { it.value }.keys)
        OutgoingSmsGuard.refresh(context.applicationContext)
        PhoneSmsBlocker.enforce(context.applicationContext)
        HookStatusBarManager(context.applicationContext).refresh()

        val enforceList = ZygiskEnforceRegistry.enforceListForPackage(targetPkg)
        Log.i(TAG, "Zygisk Enforce (copy to Zygisk Next):\n${ZygiskEnforceRegistry.formatForCopy(enforceList)}")

        Log.i(TAG, "Hooked $display ($targetPkg) phone=$phone")
        return ActiveHookManager.HookResult(
            true, targetPkg, display,
            "$display hooked — Zygisk Next Enforce me ye packages add karo:\n" +
                ZygiskEnforceRegistry.formatForCopy(enforceList)
        )
    }

    fun applyAllSelectedHooks(
        context: Context,
        configManager: ConfigManager,
        selection: Map<String, Boolean>
    ): Int {
        val selected = selection.filter { it.value }.keys.toList()
        if (selected.isEmpty()) return 0

        val current = configManager.load()
        val phone = configManager.readSpoofPhone().ifBlank { current.mockPhoneSim1 }
        val mockOn = current.enableVirtualSim || current.enableSim1Mock || current.enablePhoneSpoof

        val senderId = current.injectSenderId.trim()
        val hasSenderId = senderId.isNotBlank() && !senderId.equals("AD-TEST-S", ignoreCase = true)

        val updated = current.copy(
            hookedUpiApps = selection,
            hookUpiVerification = current.hookUpiVerification,
            hookIncomingSms = current.hookIncomingSms,
            hookOutgoingSms = current.hookOutgoingSms,
            interceptFakeSuccess = current.interceptFakeSuccess,
            prefixEnabled = current.prefixEnabled,
            prefixText = current.prefixText,
            autoExtractOtp = current.autoExtractOtp,
            autoHookForeground = true,
            overrideIncomingSender = current.overrideIncomingSender,
            enablePhoneSpoof = mockOn,
            enableSim1Mock = mockOn,
            enableVirtualSim = mockOn,
            mockPhoneSim1 = phone
        )

        if (!configManager.saveAndFlushSync(updated)) return 0

        if (mockOn) configManager.writeSpoofPhoneSync(phone)
        TelephonyInjectHelper.wakeTelephonyPipeline()
        SmsStackRefresher.refreshAfterSenderIdChange()
        ActiveHookManager.clearRestartCache()
        ActiveHookManager.forceStopAll(selected)
        ActiveHookManager.persistAllSelected(selected)
        ActiveHookManager.persistHookedScope(selected)
        OutgoingSmsGuard.refresh(context.applicationContext)
        PhoneSmsBlocker.enforce(context.applicationContext)
        HookStatusBarManager(context.applicationContext).refresh()

        Log.i(TAG, "All selected hooks active: ${selected.size} apps")
        return selected.size
    }
}
