package com.naiwa.androidpet.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.runtime.PetDiagnostics
import com.naiwa.androidpet.ui.NativeStyle

/** Transient, disjoint, non-interactive local speech. Never changes the pet bitmap or alpha. */
class SpeechBubbleWindow(private val context: Context, private val manager: WindowManager) {
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    fun show(text: String, position: Position, size: PetSize, area: ScreenArea) {
        close()
        val width = minOf(dp(210),area.width)
        val label = TextView(context).apply {
            this.text = text; textSize = 14f; setTextColor(NativeStyle.ink)
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background = NativeStyle.surface(context,0xFFFFF9E9.toInt(),16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val height = label.measuredHeight.coerceAtMost(area.height)
        val y = if (position.y-height-dp(8) >= area.top) position.y-height-dp(8) else position.y+size.height+dp(8)
        if (y < area.top || y+height > area.bottom) return
        val p = WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                (if (android.os.Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0),PixelFormat.TRANSLUCENT).apply {
            @Suppress("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            x = (position.x+(size.width-width)/2).coerceIn(area.left,area.right-width); this.y = y
            // Non-touchable overlay must stay within Android's maximum obscuring opacity.
            alpha = .8f; title = "Naiwa local speech"
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try { manager.addView(label,p); bubble = label; handler.postDelayed({ close() },1800) }
        catch (error: RuntimeException) { PetDiagnostics.event("speech.unavailable",error = error) }
    }
    fun close() {
        handler.removeCallbacksAndMessages(null)
        val old = bubble; bubble = null
        if (old != null) try { manager.removeViewImmediate(old) }
        catch (error: RuntimeException) { PetDiagnostics.event("speech.remove_failed",error = error) }
    }
    private fun dp(value: Int) = NativeStyle.dp(context,value)
}
