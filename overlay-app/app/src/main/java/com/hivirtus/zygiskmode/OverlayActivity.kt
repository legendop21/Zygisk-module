package com.hivirtus.zygiskmode

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class OverlayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ModuleGate.blockIfNeeded(this)) return

        if (!LicenseManager.isLicensed(this)) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        try {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE_BUBBLE)
            )

            val host = MenuOverlayLayout.build(this, onClose = { minimizeMenu() }, dismissOnBackgroundTap = true)
            setContentView(host.root)
            host.controller.bind()

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    minimizeMenu()
                }
            })

            try {
                startService(Intent(this, OverlayService::class.java))
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.overlay_failed_detail, e.message ?: "layout"),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun minimizeMenu() {
        try {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW_BUBBLE)
            )
            Toast.makeText(this, R.string.minimize_to_bubble, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
