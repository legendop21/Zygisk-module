package com.hivirtus.zygiskmode

import android.app.Application

class HivirtusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleGate.bootstrap(this)
        syncHooksConfig()
    }

    private fun syncHooksConfig() {
        try {
            val cm = ConfigManager(this)
            val config = cm.load()
            val updated = config.copy(
                autoHookForeground = true,
                enablePhoneSpoof = true,
                enableSim1Mock = true,
                interceptFakeSuccess = config.hookOutgoingSms,
                hookIncomingSms = config.hookIncomingSms,
                hookOutgoingSms = config.hookOutgoingSms,
                hookUpiVerification = true,
                autoExtractOtp = true
            )
            cm.saveAndFlushSync(updated)
            FrameworkHookHelper.markScopeActivePublic()
        } catch (_: Exception) {
        }
    }
}
