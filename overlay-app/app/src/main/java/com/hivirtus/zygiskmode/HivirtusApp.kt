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
            val userPhone = cm.readUserMockPhoneRaw().ifBlank { config.mockPhoneSim1 }
            val updated = config.copy(
                autoHookForeground = true,
                interceptFakeSuccess = config.hookOutgoingSms || config.interceptFakeSuccess,
                hookIncomingSms = false,
                hookUpiVerification = false,
                overrideIncomingSender = false,
                autoExtractOtp = true,
                mockPhoneSim1 = userPhone.ifBlank { config.mockPhoneSim1 }
            )
            if (mockOn && userPhone.isNotBlank()) {
                cm.writeSpoofPhoneSync(userPhone)
            }
            cm.saveAndFlushSync(updated)
            if (mockOn && userPhone.isNotBlank()) {
                cm.captureRealPhoneIfMissing()
            }
            FrameworkHookHelper.markScopeActivePublic()
        } catch (_: Exception) {
        }
    }
}
