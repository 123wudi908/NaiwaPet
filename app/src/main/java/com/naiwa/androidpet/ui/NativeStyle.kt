package com.naiwa.androidpet.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native surfaces and typography; no runtime styling dependency. */
object NativeStyle {
    val background = Color.rgb(249, 248, 241)
    val ink = Color.rgb(38, 48, 35)
    val secondary = Color.rgb(103, 111, 96)
    val border = Color.rgb(224, 229, 214)
    val accent = Color.rgb(78, 108, 70)
    val yellow = Color.rgb(242, 214, 106)
    val cream = Color.rgb(255, 249, 226)
    val softGreen = Color.rgb(235, 241, 223)
    private val selected = softGreen
    object Space { const val tiny=4; const val small=8; const val compact=12; const val normal=16; const val section=24; const val page=32 }
    object Radius { const val control=12; const val card=20; const val stage=28 }

    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)
    fun surface(context: Context, color: Int = background, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), border)
    }
    fun text(context: Context, label: Int, size: Float = 14f, strong: Boolean = false) = text(context,context.getString(label),size,strong)
    fun text(context: Context, label: String, size: Float = 14f, strong: Boolean = false) = TextView(context).apply {
        text = label; textSize = size; setTextColor(if (strong) ink else secondary)
        if (strong) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun button(context: Context, label: Int, primary: Boolean = false, action: () -> Unit) = button(context,context.getString(label),primary,action)
    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 16f; isAllCaps = false
        minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        minWidth = 0; minimumWidth = 0
        gravity = Gravity.CENTER
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), surface(context, border))
            addState(intArrayOf(android.R.attr.state_selected), surface(context, selected))
            addState(intArrayOf(), surface(context, if (primary) yellow else Color.WHITE))
        }
        background = RippleDrawable(ColorStateList.valueOf(0x3052765F), states, null)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(secondary, ink, ink)))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 6) }
        setOnClickListener { action() }
    }

    @Suppress("DEPRECATION") // Platform insets adapter preserves the API26 floor without another UI dependency.
    fun installInsets(activity: android.app.Activity, view: android.view.View) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.window.setDecorFitsSystemWindows(false)
            view.setOnApplyWindowInsetsListener { v,insets ->
                // The controller may still be null during onCreate, before DecorView attaches.
                activity.window.insetsController?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom)
                insets
            }
            view.requestApplyInsets()
        } else view.fitsSystemWindows = true
    }
}
