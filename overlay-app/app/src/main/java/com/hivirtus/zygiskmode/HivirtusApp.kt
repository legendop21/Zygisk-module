package com.hivirtus.zygiskmode

import android.app.Application

class HivirtusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleGate.bootstrap(this)
    }
}
