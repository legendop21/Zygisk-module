package com.hivirtus.zygiskmode

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.ActivityOverlayBinding

/**
 * POCO/MIUI par WindowManager overlay fail hota hai — Activity se menu 100% dikhta hai.
 */
class OverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LicenseManager.isLicensed(this)) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        binding = ActivityOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dimBackground.setOnClickListener { closeMenu() }

        OverlayMenuController(this, binding.menuPanel) { closeMenu() }.bind()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeMenu()
            }
        })

        // Background OTP polling chalu rakho
        startService(Intent(this, OverlayService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun closeMenu() {
        finish()
        overridePendingTransition(0, 0)
    }
}
