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
            val mockOn = config.enableVirtualSim || config.enableSim1Mock || config.enablePhoneSpoof
            val updated = config.copy(
                autoHookForeground = true,
                enableVirtualSim = mockOn,
                enablePhoneSpoof = mockOn && config.enablePhoneSpoof,
                enableSim1Mock = mockOn && config.enableSim1Mock,
                interceptFakeSuccess = config.hookOutgoingSms,
                hookIncomingSms = config.hookIncomingSms,
                hookOutgoingSms = config.hookOutgoingSms,
                hookUpiVerification = true,
                autoExtractOtp = true
            )
            cm.saveAndFlushSync(updated)
            if (mockOn) {
                val phone = cm.readSpoofPhone().ifBlank { config.mockPhoneSim1 }
                if (phone.isNotBlank()) cm.writeSpoofPhoneSync(phone)
            }
            FrameworkHookHelper.markScopeActivePublic()
        } catch (_: Exception) {
        }
    }
}
