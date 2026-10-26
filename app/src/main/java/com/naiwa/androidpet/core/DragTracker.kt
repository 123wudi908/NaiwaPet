package com.naiwa.androidpet.core

import kotlin.math.roundToInt

class DragTracker(private val slop: Float) {
    private var downX = 0f
    private var downY = 0f
    private var origin = Position(0, 0)
    private var active = false
    private var dragging = false

    fun down(rawX: Float, rawY: Float, position: Position) {
        downX = rawX; downY = rawY; origin = position
        active = true; dragging = false
    }

    fun move(rawX: Float, rawY: Float): Position? {
        if (!active) return null
        val dx = rawX - downX
        val dy = rawY - downY
        if (!dragging && dx * dx + dy * dy <= slop * slop) return null
        dragging = true
        return Position(origin.x + dx.roundToInt(), origin.y + dy.roundToInt())
    }

    fun finish(): Boolean {
        val result = active && dragging
        active = false; dragging = false
        return result
    }
}
