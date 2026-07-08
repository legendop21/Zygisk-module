package com.hivirtus.zygiskmode

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * Bubble tap → centered compact mod menu (2nd screenshot style).
 * Background app visible rehti hai — full-screen cut nahi.
 */
class FloatingMenuManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var menuHost: MenuOverlayLayout.MenuHost? = null

    fun isShowing(): Boolean = menuHost != null

    fun show(onClose: () -> Unit) {
        if (!Settings.canDrawOverlays(context)) return
        if (menuHost != null) return

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val host = MenuOverlayLayout.build(context.applicationContext, onClose = { hide(onClose) })
            host.controller.bind()
            menuHost = host

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                dimAmount = 0.5f
                gravity = Gravity.TOP or Gravity.START
            }

            windowManager?.addView(host.root, params)
        } catch (_: Exception) {
            hide(onClose)
        }
    }

    fun hide(onClose: (() -> Unit)? = null) {
        try {
            menuHost?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        menuHost = null
        onClose?.invoke()
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
