package com.hivirtus.zygiskmode

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.hivirtus.zygiskmode.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingStart = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionStatus()
        if (granted && pendingStart) {
            openOverlayMenu()
        } else if (pendingStart) {
            Toast.makeText(this, R.string.grant_notification_permission, Toast.LENGTH_LONG).show()
            pendingStart = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ModuleGate.blockIfNeeded(this)) return

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(PermissionHelper.notificationSettingsIntent(this))
            }
        }

        binding.btnMiuiAutostart.setOnClickListener {
            val intent = PermissionHelper.miuiAutostartIntent(this)
            if (intent != null) {
                startActivity(intent)
                Toast.makeText(this, R.string.miui_autostart_hint, Toast.LENGTH_LONG).show()
            } else {
                startActivity(PermissionHelper.overlaySettingsIntent(this))
            }
        }

        binding.btnBattery.setOnClickListener {
            try {
                startActivity(PermissionHelper.batterySettingsIntent(this))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.open_app_settings_battery, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStartOverlay.setOnClickListener {
            if (!PermissionHelper.hasNotificationPermission(this)) {
                pendingStart = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(PermissionHelper.notificationSettingsIntent(this))
                }
                return@setOnClickListener
            }
            if (!PermissionHelper.canDrawOverlay(this)) {
                pendingStart = true
                startActivity(PermissionHelper.overlaySettingsIntent(this))
                Toast.makeText(this, R.string.bubble_need_overlay, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            openOverlayMenu()
        }

        binding.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
        }

        binding.miuiSection.isVisible = PermissionHelper.isXiaomiFamily()
        binding.tvSmsStatus.visibility = View.GONE
        binding.btnGrantSms.visibility = View.GONE
        binding.tvVipStatus.text = getString(R.string.edu_open_access)
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (pendingStart && PermissionHelper.hasNotificationPermission(this) &&
            PermissionHelper.canDrawOverlay(this)
        ) {
            pendingStart = false
            openOverlayMenu()
        }
    }

    private fun updatePermissionStatus() {
        val overlayOk = PermissionHelper.canDrawOverlay(this)
        val notifOk = PermissionHelper.hasNotificationPermission(this)
        val batteryOk = PermissionHelper.isBatteryUnrestricted(this)

        binding.tvOverlayStatus.text = getString(
            if (overlayOk) R.string.permission_overlay_ok else R.string.permission_overlay_missing
        )
        binding.tvNotificationStatus.text = getString(
            if (notifOk) R.string.permission_notification_ok else R.string.permission_notification_missing
        )
        binding.tvMiuiStatus.text = getString(
            if (batteryOk) R.string.miui_battery_ok else R.string.miui_battery_missing
        )
        binding.btnStartOverlay.isEnabled = notifOk && PermissionHelper.canDrawOverlay(this)
    }

    private fun openOverlayMenu() {
        pendingStart = false
        try {
            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            startActivity(Intent(this, OverlayActivity::class.java))
            CoroutineScope(Dispatchers.Main).launch {
                val health = withContext(Dispatchers.IO) {
                    ModuleHealthChecker.check(applicationContext)
                }
                val message = if (health.moduleInstalled && health.zygiskLoaded) {
                    getString(R.string.start_hook_success)
                } else if (!health.moduleInstalled) {
                    getString(R.string.hook_start_no_module)
                } else {
                    getString(R.string.hook_start_not_loaded)
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.overlay_failed_detail, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
