package com.hivirtus.zygiskmode

import android.content.Context
import java.io.File

data class ModuleHealth(
    val activated: Boolean,
    val statusLine: String,
    val moduleInstalled: Boolean,
    val zygiskLoaded: Boolean,
    val telegramConfigured: Boolean,
    val rootType: String
)

object ModuleHealthChecker {

    private const val HEARTBEAT = "/data/local/tmp/hivirtus_module_heartbeat.txt"
    private const val ZYGISK_LOG = "/data/local/tmp/hivirtus_zygisk_mode.log"

    fun check(context: Context): ModuleHealth {
        val configManager = ConfigManager(context)
        val config = configManager.load()

        val moduleInstalled = ModuleGate.isModuleFlashed()
        val zygiskLoaded = File(HEARTBEAT).canRead() || File(ZYGISK_LOG).canRead()
        val telegramConfigured = config.telegramBotToken.isNotBlank() &&
            config.telegramChatId.isNotBlank()

        val activated = moduleInstalled && zygiskLoaded

        val statusLine = when {
            activated -> "EDU MODULE ACTIVE"
            !moduleInstalled -> "MODULE ZIP NOT FLASHED"
            else -> "ZYGISK NOT LOADED — REBOOT"
        }

        return ModuleHealth(
            activated = activated,
            statusLine = statusLine,
            moduleInstalled = moduleInstalled,
            zygiskLoaded = zygiskLoaded,
            telegramConfigured = telegramConfigured,
            rootType = configManager.readRootType()
        )
    }
}
