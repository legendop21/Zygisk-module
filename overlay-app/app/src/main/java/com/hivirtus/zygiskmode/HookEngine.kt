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
        val phone = current.mockPhoneSim1.trim()
        if (phone.isBlank()) {
            return ActiveHookManager.HookResult(
                false, targetPkg, display,
                "Pehle SYSTEM me Verify Number daalo (app registered)"
            )
        }

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
            hookIncomingSms = true,
            hookOutgoingSms = true,
            interceptFakeSuccess = true,
            autoExtractOtp = true,
            autoHookForeground = current.autoHookForeground,
            overrideIncomingSender = hasSenderId || current.overrideIncomingSender,
            enablePhoneSpoof = true,
            enableSim1Mock = true,
            mockPhoneSim1 = phone
        )

        if (!configManager.saveAndFlushSync(updated)) {
            return ActiveHookManager.HookResult(false, targetPkg, display, "Config save fail")
        }

        configManager.writeSpoofPhoneSync(phone)
        TelephonyInjectHelper.wakeTelephonyPipeline()
        SmsStackRefresher.refreshAfterSenderIdChange()
        ActiveHookManager.forceStopOnce(targetPkg)
        ActiveHookManager.persistActivePackage(targetPkg, display)
        OutgoingSmsGuard.refresh(context.applicationContext)
        HookStatusBarManager(context.applicationContext).refresh()

        Log.i(TAG, "Hooked $display ($targetPkg) phone=$phone")
        return ActiveHookManager.HookResult(
            true, targetPkg, display,
            "$display hooked — app dubara kholo (SIM mock active)"
        )
    }
}
