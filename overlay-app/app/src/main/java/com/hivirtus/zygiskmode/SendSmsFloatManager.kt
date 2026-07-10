package com.hivirtus.zygiskmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import com.hivirtus.zygiskmode.databinding.FloatSendSmsBinding

/**
 * Reference-style Send SMS float — sender ID + body, virtual SIM inject (no real SIM).
 */
class SendSmsFloatManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var binding: FloatSendSmsBinding? = null
    private val configManager = ConfigManager(context)

    fun isShowing(): Boolean = binding != null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (binding != null) return

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = FloatSendSmsBinding.inflate(LayoutInflater.from(context))
            binding = view

            val config = configManager.load()
            view.etFloatSenderId.setText(
                SmsMatcher.userSenderId(config).orEmpty().ifBlank { config.injectSenderId }
            )
            VerifyTokenPipeline.readPending()?.let { pending ->
                view.etFloatDest.setText(pending.dest)
                view.etFloatMessageBody.setText(pending.body)
            } ?: view.etFloatMessageBody.setText(config.injectMessageBody)

            view.btnSendSmsClose.setOnClickListener { hide() }
            view.btnFloatClear.setOnClickListener {
                view.etFloatMessageBody.setText("")
            }
            view.btnFloatPasteSend.setOnClickListener {
                pasteFromClipboard(view)
                sendVirtualSms(view, toastOnSuccess = true)
            }
            view.btnFloatSendSms.setOnClickListener {
                sendVirtualSms(view, toastOnSuccess = true)
            }
            view.btnFloatOpenMessages.setOnClickListener {
                val dest = view.etFloatDest.text?.toString()?.trim().orEmpty()
                val body = view.etFloatMessageBody.text?.toString()?.trim().orEmpty()
                if (!VerifyTokenPipeline.openMessagesCompose(context, dest, body)) {
                    Toast.makeText(context, R.string.open_messages_failed, Toast.LENGTH_SHORT).show()
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (72 * context.resources.displayMetrics.density).toInt()
            }

            windowManager?.addView(view.root, params)
        } catch (_: Exception) {
            hide()
        }
    }

    fun hide() {
        try {
            binding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        binding = null
    }

    private fun pasteFromClipboard(view: FloatSendSmsBinding) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            if (text.isNotBlank()) {
                view.etFloatMessageBody.setText(text)
            }
        } catch (_: Exception) {}
    }

    private fun sendVirtualSms(view: FloatSendSmsBinding, toastOnSuccess: Boolean) {
        val sender = view.etFloatSenderId.text?.toString()?.trim().orEmpty()
        val body = view.etFloatMessageBody.text?.toString()?.trim().orEmpty()
        if (sender.isBlank() || body.isBlank()) {
            Toast.makeText(context, R.string.enter_message_body, Toast.LENGTH_SHORT).show()
            return
        }

        configManager.save(
            configManager.load().copy(
                injectSenderId = sender,
                injectMessageBody = body
            )
        )

        val ok = VirtualSmsPipeline.injectIncoming(context, sender, body)
        if (toastOnSuccess) {
            Toast.makeText(
                context,
                if (ok) R.string.sms_injected else R.string.save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}
