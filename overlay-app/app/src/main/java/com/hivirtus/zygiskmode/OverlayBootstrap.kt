package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.os.Build

object OverlayBootstrap {

    fun start(context: Context) {
        if (!ModuleGate.isModuleFlashed()) return
        if (!LicenseManager.isLicensed(context)) return

        val app = context.applicationContext
        try {
            val intent = Intent(app, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (_: Exception) {}
    }
}
