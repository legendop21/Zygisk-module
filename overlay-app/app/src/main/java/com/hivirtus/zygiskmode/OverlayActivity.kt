package com.hivirtus.zygiskmode

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding

/**
 * POCO/MIUI: translucent theme + include tag se layout inflate fail hota tha.
 * Menu direct OverlayMenuBinding se load hota hai — full Material theme ke saath.
 */
class OverlayActivity : AppCompatActivity() {

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

        try {
            val menuBinding = OverlayMenuBinding.inflate(layoutInflater)
            val menuWidthPx = (340f * resources.displayMetrics.density).toInt()

            val container = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#CC12151C"))
                setOnClickListener { closeMenu() }
            }

            menuBinding.root.setOnClickListener { /* menu area — container ko click mat bhejo */ }

            container.addView(
                menuBinding.root,
                FrameLayout.LayoutParams(menuWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
            setContentView(container)

            OverlayMenuController(this, menuBinding) { closeMenu() }.bind()

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeMenu()
                }
            })

            try {
                startService(Intent(this, OverlayService::class.java))
            } catch (_: Exception) {
                // Menu dikhe — background service optional
            }
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

    private fun closeMenu() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
