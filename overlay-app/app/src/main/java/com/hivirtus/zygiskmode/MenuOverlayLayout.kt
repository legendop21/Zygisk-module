package com.hivirtus.zygiskmode

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hivirtus.zygiskmode.databinding.OverlayMenuBinding

/**
 * Bubble tap / notification → centered compact mod menu (screenshot style).
 * App background visible — full-width cut nahi hota.
 */
object MenuOverlayLayout {

    private const val MENU_WIDTH_DP = 360
    private const val MAX_HEIGHT_FRACTION = 0.88f

    data class MenuHost(
        val root: FrameLayout,
        val menuBinding: OverlayMenuBinding,
        val controller: OverlayMenuController
    )

    fun themedContext(context: Context): Context {
        return ContextThemeWrapper(context, R.style.Theme_HivirtusZygiskMode)
    }

    fun build(
        context: Context,
        onClose: () -> Unit,
        dismissOnBackgroundTap: Boolean = true
    ): MenuHost {
        val themed = themedContext(context)
        val metrics = themed.resources.displayMetrics
        val menuWidthPx = (MENU_WIDTH_DP * metrics.density).toInt()
        val maxMenuHeightPx = (metrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()

        val root = FrameLayout(themed).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#B3000000"))
            if (dismissOnBackgroundTap) {
                setOnClickListener { onClose() }
            }
        }

        val menuBinding = OverlayMenuBinding.inflate(LayoutInflater.from(themed))
        menuBinding.root.setOnClickListener { /* consume taps inside menu */ }
        val scrollMaxPx = minOf(maxMenuHeightPx, (420 * metrics.density).toInt())
        menuBinding.tabContentScroll.apply {
            val params = layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.height = scrollMaxPx
            layoutParams = params
        }

        root.addView(
            menuBinding.root,
            FrameLayout.LayoutParams(menuWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        val controller = OverlayMenuController(themed, menuBinding, onClose)
        return MenuHost(root, menuBinding, controller)
    }
}
