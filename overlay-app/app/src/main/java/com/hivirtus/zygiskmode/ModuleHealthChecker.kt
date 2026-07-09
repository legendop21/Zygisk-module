package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File

data class ModuleHealth(
    val activated: Boolean,
    val statusLine: String,
    val moduleInstalled: Boolean,
    val zygiskLoaded: Boolean,
    val phoneSpoofReady: Boolean,
    val senderIdSet: Boolean,
    val licensed: Boolean,
    val spoofPhone: String,
    val rootType: String
)

object ModuleHealthChecker {

    private const val MODULE_PROP = "/data/adb/modules/hivirtus_zygisk_mode/module.prop"
    private const val HEARTBEAT = "/data/local/tmp/hivirtus_module_heartbeat.txt"
    private const val ZYGISK_LOG = "/data/local/tmp/hivirtus_zygisk_mode.log"
    private const val ACTIVE_UPI = "/data/local/tmp/hivirtus_active_upi.txt"

    fun check(context: Context): ModuleHealth {
        val configManager = ConfigManager(context)
        val config = configManager.load()

        val moduleInstalled = ModuleGate.isModuleFlashed(context)
        val zygiskLoaded = ModuleGate.isZygiskLoaded() ||
            File(HEARTBEAT).canRead() ||
            File(ZYGISK_LOG).canRead() ||
            File(ACTIVE_UPI).canRead()

        val spoofPhone = configManager.readSpoofPhone().trim()
        val phoneSpoofReady = when {
            !config.enablePhoneSpoof && !config.enableSim1Mock && !config.enableSim2Mock -> true
            spoofPhone.isNotBlank() -> true
            config.mockPhoneSim1.isNotBlank() -> true
            else -> false
        }

        val senderIdSet = SmsMatcher.userSenderId(config) != null
        val licensed = LicenseManager.isLicensed(context)

        val activated = moduleInstalled &&
            zygiskLoaded &&
            phoneSpoofReady

        val statusLine = if (activated) "✅ MODULE ACTIVATED" else "❌ NOT ACTIVATE"

        return ModuleHealth(
            activated = activated,
            statusLine = statusLine,
            moduleInstalled = moduleInstalled,
            zygiskLoaded = zygiskLoaded,
            phoneSpoofReady = phoneSpoofReady,
            senderIdSet = senderIdSet,
            licensed = licensed,
            spoofPhone = spoofPhone.ifBlank { config.mockPhoneSim1 },
            rootType = configManager.readRootType()
        )
    }
}
