package com.hivirtus.zygiskmode

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {

    private val configManager = ConfigManager(context)
    private val deviceIdManager = DeviceIdManager(context)

    fun createBackup(): String? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val config = configManager.load()
            val json = JSONObject().apply {
                put("config", buildConfigJson(config))
                put("license", LicenseManager.getSavedKey(context).orEmpty())
                put("timestamp", stamp)
                put("version", "2.5.0")
            }
            val payload = json.toString(2)

            var savedPath: String? = null
            backupRoots().forEach { root ->
                try {
                    root.mkdirs()
                    val folder = File(root, "backup_$stamp")
                    folder.mkdirs()
                    File(folder, "hivirtus_backup.json").writeText(payload)
                    File(root, "hivirtus_backup.json").writeText(payload)
                    if (savedPath == null) savedPath = folder.absolutePath
                } catch (_: Exception) {}
            }

            configManager.save(config)
            savedPath
        } catch (_: Exception) {
            null
        }
    }

    fun restoreLatest(): Boolean {
        for (root in backupRoots()) {
            val direct = File(root, "hivirtus_backup.json")
            if (direct.exists() && restoreFromFile(direct)) return true

            val newest = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                ?.maxByOrNull { it.name }
            val nested = newest?.let { File(it, "hivirtus_backup.json") }
            if (nested?.exists() == true && restoreFromFile(nested)) return true
        }
        return false
    }

    fun listBackups(): List<File> {
        val all = mutableListOf<File>()
        backupRoots().forEach { root ->
            root.mkdirs()
            root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                ?.let { all.addAll(it) }
        }
        return all.sortedByDescending { it.name }
    }

    private fun backupRoots(): List<File> {
        val dirs = linkedSetOf<File>()
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "HivirtusBackup")) }
        dirs.add(File(context.filesDir, "HivirtusBackup"))
        try {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                dirs.add(File(Environment.getExternalStorageDirectory(), "HivirtusBackup"))
                dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HivirtusBackup"))
            }
        } catch (_: Exception) {}
        return dirs.toList()
    }

    private fun restoreFromFile(file: File): Boolean {
        return try {
            val json = JSONObject(file.readText())
            val configObj = json.getJSONObject("config")
            restoreConfig(configObj)
            val license = json.optString("license", "")
            if (license.isNotBlank()) LicenseManager.activate(context, license)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreConfig(obj: JSONObject) {
        val hooked = mutableMapOf<String, Boolean>()
        if (obj.has("hooked_upi_apps")) {
            val hookedObj = obj.getJSONObject("hooked_upi_apps")
            UpiAppRegistry.ALL.forEach { app ->
                hooked[app.packageName] = hookedObj.optBoolean(app.packageName, true)
            }
        } else {
            hooked.putAll(UpiAppRegistry.defaultHookMap())
        }

        val spoofId = obj.optString("spoof_android_id", "")
        val config = ModuleConfig(
            hideRoot = obj.optBoolean("hide_root", true),
            hideDeveloper = obj.optBoolean("hide_developer", true),
            hideMagisk = obj.optBoolean("hide_magisk", true),
            hideKernelSu = obj.optBoolean("hide_kernelsu", true),
            hideApatch = obj.optBoolean("hide_apatch", true),
            hideSukisu = obj.optBoolean("hide_sukisu", true),
            hideAllRootApps = obj.optBoolean("hide_all_root_apps", true),
            enableSim1Mock = obj.optBoolean("enable_sim1_mock", false),
            enableSim2Mock = obj.optBoolean("enable_sim2_mock", false),
            enablePhoneSpoof = obj.optBoolean("enable_phone_spoof", false),
            mockCountryIso = obj.optString("mock_country_iso", "in"),
            mockPhoneSim1 = obj.optString("mock_phone_sim1", "+919876543210"),
            mockPhoneSim2 = obj.optString("mock_phone_sim2", "+919876543211"),
            hookIncomingSms = obj.optBoolean("hook_incoming_sms", true),
            hookOutgoingSms = obj.optBoolean("hook_outgoing_sms", true),
            hookUpiVerification = obj.optBoolean("hook_upi_verification", true),
            hookedUpiApps = hooked,
            autoExtractOtp = obj.optBoolean("auto_extract_otp", true),
            autoForwardToken = obj.optBoolean("auto_forward_token", true),
            forwardUrl = obj.optString("forward_url", ""),
            telegramBotToken = obj.optString("telegram_bot_token", ""),
            telegramChatId = obj.optString("telegram_chat_id", ""),
            spoofAndroidId = spoofId,
            enableDeviceIdSpoof = obj.optBoolean("enable_device_id_spoof", spoofId.isNotBlank()),
            injectSenderId = obj.optString("inject_sender_id", "AD-TEST-S"),
            injectMessageBody = obj.optString("inject_message_body", "")
        )
        configManager.save(config)
        if (spoofId.isNotBlank()) {
            try {
                File("/data/local/tmp/hivirtus_spoof_android_id.txt").writeText(spoofId)
                File("/data/local/tmp/hivirtus_change_device_id.cmd").writeText("CHANGE_ID|$spoofId")
            } catch (_: Exception) {}
        }
    }

    private fun buildConfigJson(config: ModuleConfig): JSONObject {
        val spoofId = config.spoofAndroidId.ifBlank { deviceIdManager.getCurrentSpoofId().takeIf { it != "Not set" }.orEmpty() }
        val hooked = JSONObject()
        config.hookedUpiApps.forEach { (k, v) -> hooked.put(k, v) }
        return JSONObject().apply {
            put("hide_root", config.hideRoot)
            put("hide_developer", config.hideDeveloper)
            put("hide_magisk", config.hideMagisk)
            put("hide_kernelsu", config.hideKernelSu)
            put("hide_apatch", config.hideApatch)
            put("hide_sukisu", config.hideSukisu)
            put("hide_all_root_apps", config.hideAllRootApps)
            put("enable_sim1_mock", config.enableSim1Mock)
            put("enable_sim2_mock", config.enableSim2Mock)
            put("enable_phone_spoof", config.enablePhoneSpoof)
            put("mock_country_iso", config.mockCountryIso)
            put("mock_phone_sim1", config.mockPhoneSim1)
            put("mock_phone_sim2", config.mockPhoneSim2)
            put("hook_incoming_sms", config.hookIncomingSms)
            put("hook_outgoing_sms", config.hookOutgoingSms)
            put("hook_upi_verification", config.hookUpiVerification)
            put("auto_extract_otp", config.autoExtractOtp)
            put("auto_forward_token", config.autoForwardToken)
            put("forward_url", config.forwardUrl)
            put("telegram_bot_token", config.telegramBotToken)
            put("telegram_chat_id", config.telegramChatId)
            put("spoof_android_id", spoofId)
            put("enable_device_id_spoof", config.enableDeviceIdSpoof || spoofId.isNotBlank())
            put("hooked_upi_apps", hooked)
            put("inject_sender_id", config.injectSenderId)
            put("inject_message_body", config.injectMessageBody)
        }
    }
}
