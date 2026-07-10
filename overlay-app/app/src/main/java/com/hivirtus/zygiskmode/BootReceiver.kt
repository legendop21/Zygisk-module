package com.hivirtus.zygiskmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_MODULE_BOOT -> OverlayBootstrap.start(context)
        }
    }

    companion object {
        const val ACTION_MODULE_BOOT = "com.hivirtus.zygiskmode.BOOT_START"
    }
}
