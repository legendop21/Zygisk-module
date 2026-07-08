package com.hivirtus.zygiskmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Hooked UPI apps ke liye OTP auto-read helper — clipboard + runtime file */
object OtpAutoFillHelper {

    private const val AUTO_FILL_FILE = "/data/local/tmp/hivirtus_autofill_otp.txt"
    private const val SENDER_FILE = "/data/local/tmp/hivirtus_autofill_sender.txt"

    fun onHookedOtpCaptured(context: Context, config: ModuleConfig, otp: LastOtp) {
        val digits = SmsMatcher.extractOtpDigits(otp.body) ?: return
        if (digits.length !in 4..8) return

        val senderId = SmsMatcher.userSenderId(config)
            ?: SmsMatcher.interceptDisplay(config, otp.rawPeer, ConfigManager(context))

        try {
            val appDir = context.getExternalFilesDir(null) ?: context.filesDir
            appDir.mkdirs()
            java.io.File(appDir, "hivirtus_autofill_otp.txt").writeText(digits)
            java.io.File(appDir, "hivirtus_autofill_sender.txt").writeText(senderId)
        } catch (_: Exception) {}

        try {
            java.io.File(AUTO_FILL_FILE).writeText(digits)
            java.io.File(SENDER_FILE).writeText(senderId)
        } catch (_: Exception) {}

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("hivirtus_otp", digits))
        } catch (_: Exception) {}
    }
}
