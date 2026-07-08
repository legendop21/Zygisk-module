package com.hivirtus.zygiskmode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File

object SecurityInfoHelper {

    data class DeviceSecurityStatus(
        val developerOptionsEnabled: Boolean,
        val rootIndicators: List<String>,
        val moduleInstalled: Boolean,
        val zygiskHeartbeat: Boolean
    )

    fun readStatus(context: Context): DeviceSecurityStatus {
        val devEnabled = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (_: Exception) {
            false
        }

        val rootIndicators = mutableListOf<String>()
        val probePaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/apatch"
        )
        probePaths.forEach { path ->
            if (File(path).exists()) rootIndicators.add(path)
        }

        return DeviceSecurityStatus(
            developerOptionsEnabled = devEnabled,
            rootIndicators = rootIndicators,
            moduleInstalled = ModuleGate.isModuleFlashed(),
            zygiskHeartbeat = File("/data/local/tmp/hivirtus_module_heartbeat.txt").canRead()
        )
    }

    fun formatAppInfo(context: Context): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = pm.getPackageInfo(pkg, 0)
        val version = info.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val installer = pm.getInstallerPackageName(pkg) ?: "sideload/unknown"

        return buildString {
            appendLine("Package: $pkg")
            appendLine("Version: $version ($versionCode)")
            appendLine("Installer: $installer")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        }.trim()
    }

    fun formatSecurityStatus(status: DeviceSecurityStatus): String {
        return buildString {
            appendLine("Developer options: ${if (status.developerOptionsEnabled) "ON" else "OFF"}")
            appendLine("Root indicators: ${if (status.rootIndicators.isEmpty()) "none found" else status.rootIndicators.joinToString()}")
            appendLine("Edu module ZIP: ${if (status.moduleInstalled) "installed" else "missing"}")
            appendLine("Zygisk heartbeat: ${if (status.zygiskHeartbeat) "active" else "not detected"}")
            appendLine()
            append("Note: This lab only reads status. It does not hide root or hook other apps.")
        }.trim()
    }
}
