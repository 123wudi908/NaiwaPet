package com.naiwa.androidpet.animation

import kotlin.math.roundToInt

data class DecodePlan(val sample: Int, val width: Int, val height: Int)

/** Decode above the display resolution, then downscale once while retaining the source ratio. */
object DecodeSizing {
    fun plan(width: Int, height: Int, target: Int): DecodePlan {
        require(width > 0 && height > 0 && target > 0)
        val longest = maxOf(width,height)
        var sample = 1
        while (longest / (sample * 2L) >= target) sample *= 2
        val scale = minOf(1.0,target.toDouble()/longest)
        return DecodePlan(sample,(width*scale).roundToInt().coerceAtLeast(1),
            (height*scale).roundToInt().coerceAtLeast(1))
    }
}
