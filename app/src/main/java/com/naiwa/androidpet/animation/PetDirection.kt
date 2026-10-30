package com.naiwa.androidpet.animation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Screen coordinates: +x is right, +y is down/towards the viewer. */
enum class PetDirection(val directory: String) {
    FRONT("front"), FRONT_LEFT("front_left"), LEFT("left"), BACK_LEFT("back_left"),
    BACK("back"), BACK_RIGHT("back_right"), RIGHT("right"), FRONT_RIGHT("front_right")
}

/** Retains facing at rest, accumulates slow movement, and rejects sector-edge jitter. */
class DirectionState {
    var direction = PetDirection.FRONT
        private set
    private var dx = 0.0
    private var dy = 0.0

    fun moveBy(deltaX: Double, deltaY: Double): PetDirection {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return direction
        if (deltaX == 0.0 && deltaY == 0.0) return direction
        dx += deltaX; dy += deltaY
        if (hypot(dx, dy) < 2.0) return direction
        val angle = (Math.toDegrees(atan2(-dx, dy)) + 360.0) % 360.0
        resetMotion()
        val distance = abs((angle - direction.ordinal * 45.0 + 540.0) % 360.0 - 180.0)
        // 22.5 degree sectors plus 8 degrees of hysteresis around the old facing.
        if (distance > 30.5) direction = PetDirection.entries[floor((angle + 22.5) / 45.0).toInt() % 8]
        return direction
    }

    fun resetMotion() { dx = 0.0; dy = 0.0 }
}
