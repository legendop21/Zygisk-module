package com.hivirtus.zygiskmode

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingStart = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionStatus()
        if (granted && pendingStart) {
            startOverlayService()
        } else if (pendingStart) {
            Toast.makeText(this, R.string.grant_notification_permission, Toast.LENGTH_LONG).show()
            pendingStart = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LicenseManager.isLicensed(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
            Toast.makeText(this, R.string.grant_overlay_permission, Toast.LENGTH_LONG).show()
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(PermissionHelper.notificationSettingsIntent(this))
            }
        }

        binding.btnStartOverlay.setOnClickListener {
            if (!PermissionHelper.canDrawOverlay(this)) {
                startActivity(PermissionHelper.overlaySettingsIntent(this))
                Toast.makeText(this, R.string.grant_overlay_permission, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!PermissionHelper.hasNotificationPermission(this)) {
                pendingStart = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(PermissionHelper.notificationSettingsIntent(this))
                }
                Toast.makeText(this, R.string.grant_notification_permission, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startOverlayService()
        }

        binding.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
        }

        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (pendingStart && PermissionHelper.allGranted(this)) {
            pendingStart = false
            startOverlayService()
        }
    }

    private fun updatePermissionStatus() {
        val overlayOk = PermissionHelper.canDrawOverlay(this)
        val notifOk = PermissionHelper.hasNotificationPermission(this)

        binding.tvOverlayStatus.text = getString(
            if (overlayOk) R.string.permission_overlay_ok else R.string.permission_overlay_missing
        )
        binding.tvNotificationStatus.text = getString(
            if (notifOk) R.string.permission_notification_ok else R.string.permission_notification_missing
        )
        binding.btnStartOverlay.isEnabled = overlayOk && notifOk
    }

    private fun startOverlayService() {
        pendingStart = false
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_LONG).show()
            // Do NOT background app immediately — wait for service to startForeground
            binding.root.postDelayed({ moveTaskToBack(true) }, 800)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.overlay_failed_detail, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
