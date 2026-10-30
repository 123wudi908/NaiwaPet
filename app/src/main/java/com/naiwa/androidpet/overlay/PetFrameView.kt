package com.naiwa.androidpet.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import com.naiwa.androidpet.R

class PetFrameView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var frame: Bitmap? = null
    private var frameOffsetX = 0f
    private var frameOffsetY = 0f
    val hasFrame: Boolean get() = frame != null
    var mirrored: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var onWindowVisible: ((Boolean) -> Unit)? = null

    init {
        isClickable = true; contentDescription = context.getString(R.string.pet_accessibility)
        alpha = 1f
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** Offsets are fractions of the source content canvas, independent of bitmap decode size. */
    fun setFrame(bitmap: Bitmap, offsetX: Float = 0f, offsetY: Float = 0f) {
        frame = bitmap
        frameOffsetX = offsetX
        frameOffsetY = offsetY
        source.set(0, 0, bitmap.width, bitmap.height)
        invalidate()
    }

    fun clearFrame() { frame = null; invalidate() }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onWindowVisible?.invoke(visibility == VISIBLE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // CLEAR is scoped to this saved layer, never the parent window or sibling panel.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.alpha = 255
        val current = frame
        if (current != null) {
            // Optional actions may have another aspect ratio. Keep proportions and a stable floor.
            val scale = minOf(width.toFloat() / current.width, height.toFloat() / current.height)
            val drawnWidth = current.width * scale
            val drawnHeight = current.height * scale
            val left = (width - drawnWidth) / 2f
            destination.set(left, height - drawnHeight, left + drawnWidth, height.toFloat())
            destination.offset(drawnWidth * frameOffsetX, drawnHeight * frameOffsetY)
            paint.color = Color.WHITE
            if (mirrored) { canvas.translate(width.toFloat(),0f); canvas.scale(-1f,1f) }
            canvas.drawBitmap(current, source, destination, paint)
        }
        canvas.restoreToCount(layer)
    }

    override fun performClick(): Boolean = super.performClick()
}
