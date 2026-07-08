package com.hivirtus.zygiskmode

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding

/**
 * Bubble tap → bottom half overlay menu. Full Activity nahi — app cut nahi hoti.
 */
class FloatingMenuManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var menuController: OverlayMenuController? = null

    fun isShowing(): Boolean = rootView != null

    fun show(onClose: () -> Unit) {
        if (!Settings.canDrawOverlays(context)) return
        if (rootView != null) return

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = context.resources.displayMetrics
            val menuHeight = (metrics.heightPixels * 0.52f).toInt()

            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
                setOnClickListener { hide(onClose) }
            }

            val menuBinding = OverlayMenuBinding.inflate(LayoutInflater.from(context))
            menuBinding.root.setOnClickListener { /* consume */ }

            container.addView(
                menuBinding.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    menuHeight,
                    Gravity.BOTTOM
                )
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                dimAmount = 0.45f
                gravity = Gravity.TOP or Gravity.START
            }

            menuController = OverlayMenuController(context.applicationContext, menuBinding) {
                hide(onClose)
            }.also { it.bind() }

            windowManager?.addView(container, params)
            rootView = container
        } catch (_: Exception) {
            hide(onClose)
        }
    }

    fun hide(onClose: (() -> Unit)? = null) {
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        rootView = null
        menuController = null
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
