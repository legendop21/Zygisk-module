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
            val phone = config.mockPhoneSim1.trim()
            if (phone.isNotBlank()) {
                cm.writeSpoofPhoneSync(phone)
            }
            val updated = config.copy(
                enablePhoneSpoof = true,
                enableSim1Mock = true,
                interceptFakeSuccess = true,
                hookOutgoingSms = true,
                hookIncomingSms = true,
                mockPhoneSim1 = phone.ifBlank { config.mockPhoneSim1 }
            )
            cm.saveAndFlushSync(updated)
        } catch (_: Exception) {
        }
    }
}
