package com.hivirtus.zygiskmode

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.hivirtus.zygiskmode.databinding.HookStatusBarBinding

/**
 * Hooked app foreground pe bottom pill — "Hook Incoming SMS: ON" (screenshot style).
 */
class HookStatusBarManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var binding: HookStatusBarBinding? = null
    private var visibleForPkg: String? = null

    fun refresh() {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }

        val activePkg = ActiveHookManager.readActivePackage()
        val config = ConfigManager(context).load()
        if (!config.hookIncomingSms && !config.hookOutgoingSms) {
            hide()
            return
        }

        val fg = ForegroundAppHelper.foregroundPackage(context)
        if (fg.isNullOrBlank() || !ActiveHookManager.isSelectedHooked(config, fg)) {
            hide()
            return
        }

        show(fg, config)
    }

    private fun show(packageName: String, config: ModuleConfig) {
        try {
            if (binding == null) {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                binding = HookStatusBarBinding.inflate(LayoutInflater.from(context))
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (16 * context.resources.displayMetrics.density).toInt()
                }
                windowManager?.addView(binding!!.root, params)
            }

            if (visibleForPkg != packageName) {
                binding?.ivHookAppIcon?.setImageDrawable(
                    runCatching {
                        context.packageManager.getApplicationIcon(packageName)
                    }.getOrElse {
                        context.getDrawable(R.drawable.ic_hivirtus_logo)
                    }
                )
                visibleForPkg = packageName
            }

            binding?.tvHookStatusLine?.text = buildStatusText(config)
        } catch (_: Exception) {
            hide()
        }
    }

    private fun buildStatusText(config: ModuleConfig): String {
        val incoming = config.hookIncomingSms
        val outgoing = config.hookOutgoingSms
        return when {
            incoming && outgoing -> context.getString(R.string.hook_status_both_on)
            incoming -> context.getString(R.string.hook_status_incoming_on)
            outgoing -> context.getString(R.string.hook_status_outgoing_on)
            else -> context.getString(R.string.hook_status_incoming_on)
        }
    }

    fun hide() {
        try {
            binding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        binding = null
        visibleForPkg = null
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
