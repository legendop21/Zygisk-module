package com.hivirtus.zygiskmode

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.hivirtus.zygiskmode.databinding.FloatBubbleBinding
import kotlin.math.abs

class FloatingBubbleManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var binding: FloatBubbleBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    fun isShowing(): Boolean = binding != null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (binding != null) return

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bubble = FloatBubbleBinding.inflate(LayoutInflater.from(context))
            binding = bubble

            val display = context.resources.displayMetrics
            val sideX = display.widthPixels - (72 * display.density).toInt()

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = sideX
                y = (display.heightPixels * 0.35f).toInt()
            }

            bubble.root.setOnTouchListener { _, event ->
                handleTouch(event)
            }

            windowManager?.addView(bubble.root, layoutParams)
        } catch (_: Exception) {
            hide()
        }
    }

    fun hide() {
        try {
            binding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        binding = null
        layoutParams = null
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val lp = layoutParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = lp.x
                initialY = lp.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) isDragging = true
                lp.x = initialX + dx
                lp.y = initialY + dy
                windowManager?.updateViewLayout(binding?.root, lp)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    openMenu()
                } else {
                    snapToSide(lp)
                }
                return true
            }
        }
        return false
    }

    private fun snapToSide(lp: WindowManager.LayoutParams) {
        val display = context.resources.displayMetrics
        val mid = display.widthPixels / 2
        lp.x = if (lp.x + (29 * display.density).toInt() < mid) {
            (8 * display.density).toInt()
        } else {
            display.widthPixels - (66 * display.density).toInt()
        }
        windowManager?.updateViewLayout(binding?.root, lp)
    }

    private fun openMenu() {
        hide()
        val intent = Intent(context, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
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
