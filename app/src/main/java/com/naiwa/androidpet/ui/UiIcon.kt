package com.naiwa.androidpet.ui

import android.content.Context
import android.graphics.*
import android.view.View

enum class IconKind { HOME, BOOK, PLAY, HEART, SETTINGS, BACK }

/** Small original line icons, one stroke width and no font or external asset dependency. */
class UiIcon @JvmOverloads constructor(context: Context, private val kind: IconKind = IconKind.HOME, var tint: Int = NativeStyle.ink) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save(); val side = minOf(width,height).toFloat()
        canvas.translate((width-side)/2,(height-side)/2); canvas.scale(side/24,side/24)
        paint.color = tint; path.reset()
        when (kind) {
            IconKind.HOME -> {
                path.moveTo(3f,11f);path.lineTo(12f,3f);path.lineTo(21f,11f)
                path.moveTo(5f,10f);path.lineTo(5f,21f);path.lineTo(10f,21f);path.lineTo(10f,15f)
                path.lineTo(14f,15f);path.lineTo(14f,21f);path.lineTo(19f,21f);path.lineTo(19f,10f)
            }
            IconKind.BOOK -> {
                path.moveTo(12f,5f);path.cubicTo(9f,3f,5f,3f,3f,5f);path.lineTo(3f,20f)
                path.cubicTo(6f,18f,9f,18f,12f,20f);path.cubicTo(15f,18f,18f,18f,21f,20f)
                path.lineTo(21f,5f);path.cubicTo(18f,3f,15f,3f,12f,5f);path.lineTo(12f,20f)
            }
            IconKind.PLAY -> {
                canvas.drawRoundRect(2.5f,5f,21.5f,20f,5f,5f,paint)
                path.moveTo(7f,10f);path.lineTo(7f,15f);path.moveTo(4.5f,12.5f);path.lineTo(9.5f,12.5f)
                canvas.drawCircle(16f,10.5f,.8f,paint);canvas.drawCircle(18.5f,14f,.8f,paint)
            }
            IconKind.HEART -> {
                path.moveTo(12f,21f);path.cubicTo(9f,18f,2.5f,13.5f,3f,8f)
                path.cubicTo(3.5f,2f,10f,2f,12f,7f);path.cubicTo(14f,2f,20.5f,2f,21f,8f)
                path.cubicTo(21.5f,13.5f,15f,18f,12f,21f)
            }
            IconKind.SETTINGS -> {
                canvas.drawCircle(12f,12f,3f,paint);canvas.drawCircle(12f,12f,8f,paint)
                repeat(8) { i ->
                    val angle = i*Math.PI/4
                    canvas.drawLine(12f+8f*kotlin.math.cos(angle).toFloat(),12f+8f*kotlin.math.sin(angle).toFloat(),
                        12f+10f*kotlin.math.cos(angle).toFloat(),12f+10f*kotlin.math.sin(angle).toFloat(),paint)
                }
            }
            IconKind.BACK -> { path.moveTo(15f,4f);path.lineTo(7f,12f);path.lineTo(15f,20f) }
        }
        canvas.drawPath(path,paint);canvas.restoreToCount(save)
    }
}
