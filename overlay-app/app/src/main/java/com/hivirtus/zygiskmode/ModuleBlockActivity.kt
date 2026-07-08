package com.hivirtus.zygiskmode

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.ActivityModuleBlockBinding

class ModuleBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModuleBlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleGate.bootstrap(this)

        if (ModuleGate.isModuleFlashed(this)) {
            openApp()
            return
        }

        binding = ActivityModuleBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        binding.btnRetryModule.setOnClickListener {
            ModuleGate.bootstrap(this)
            if (ModuleGate.isModuleFlashed(this)) {
                openApp()
            } else {
                Toast.makeText(this, R.string.module_still_missing, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnLsposedContinue.setOnClickListener {
            if (!ModuleGate.isLsposedManagerInstalled(this)) {
                Toast.makeText(this, R.string.lsposed_not_installed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ModuleGate.confirmLsposedMode(this)
            Toast.makeText(this, R.string.lsposed_mode_confirmed, Toast.LENGTH_LONG).show()
            openApp()
        }

        binding.btnExitApp.setOnClickListener {
            finishAffinity()
        }
    }

    private fun openApp() {
        startActivity(Intent(this, LicenseActivity::class.java))
        finish()
    }
}
