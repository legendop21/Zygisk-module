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

            val menuBinding = OverlayMenuBinding.inflate(layoutInflater)
            val menuHeight = (resources.displayMetrics.heightPixels * 0.52f).toInt()

            val container = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#99000000"))
                setOnClickListener { minimizeMenu() }
            }

            menuBinding.root.setOnClickListener { /* menu area */ }

            container.addView(
                menuBinding.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    menuHeight,
                    Gravity.BOTTOM
                )
            )
            setContentView(container)

            OverlayMenuController(this, menuBinding) { minimizeMenu() }.bind()

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
