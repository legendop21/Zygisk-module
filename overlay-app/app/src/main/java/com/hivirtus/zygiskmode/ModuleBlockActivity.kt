package com.hivirtus.zygiskmode

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.hivirtus.zygiskmode.databinding.ActivityModuleBlockBinding

class ModuleBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModuleBlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ModuleGate.isModuleFlashed()) {
            startActivity(android.content.Intent(this, LicenseActivity::class.java))
            finish()
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
            if (ModuleGate.isModuleFlashed()) {
                startActivity(android.content.Intent(this, LicenseActivity::class.java))
                finish()
            }
        }

        binding.btnExitApp.setOnClickListener {
            finishAffinity()
        }
    }
}
