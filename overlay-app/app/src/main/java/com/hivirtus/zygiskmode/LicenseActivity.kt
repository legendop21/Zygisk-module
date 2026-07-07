package com.hivirtus.zygiskmode

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.ActivityLicenseBinding

class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LicenseManager.isLicensed(this)) {
            openMain()
            return
        }

        // Auto-restore backup after clear data
        BackupManager(this).restoreLatest()

        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnActivate.setOnClickListener {
            val key = binding.etLicenseKey.text?.toString().orEmpty()
            if (LicenseManager.activate(this, key)) {
                BackupManager(this).createBackup()
                Toast.makeText(this, R.string.license_activated, Toast.LENGTH_SHORT).show()
                openMain()
            } else {
                Toast.makeText(this, R.string.license_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
