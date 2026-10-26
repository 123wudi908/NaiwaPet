package com.naiwa.androidpet.overlay

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.naiwa.androidpet.core.ScreenArea

class ScreenBoundsReader(private val manager: WindowManager) {
    @Suppress("DEPRECATION")
    fun read(root: View? = null): ScreenArea {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val b = metrics.bounds
            return ScreenArea(b.left + insets.left, b.top + insets.top,
                (b.right - insets.right).coerceAtLeast(b.left + insets.left + 1),
                (b.bottom - insets.bottom).coerceAtLeast(b.top + insets.top + 1))
        }
        val size = Point()
        manager.defaultDisplay.getRealSize(size)
        // Pre-30 windows use WM's default safe parent (no LAYOUT_IN_SCREEN, cutout NEVER).
        // getWindowVisibleDisplayFrame is global; params x/y are relative to its safe parent.
        if (root?.isAttachedToWindow == true) {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            if (!visible.isEmpty) return ScreenArea(0, 0, visible.width(), visible.height())
        }
        // Provisional only. The controller keeps the window invisible until attached/layout-refit.
        return ScreenArea(0, 0, size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
    }
}
