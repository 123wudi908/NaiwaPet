package com.naiwa.androidpet.companion

import kotlin.math.abs

data class ComboFeedback(val count: Int, val level: Int, val animate: Boolean, val accepted: Boolean)

/** Monotonic gesture time; this does not own another timer or animation loop. */
class ComboTracker {
    private var lastTap = -1L
    private var lastAnimation = -1L
    private var lastLevel = 0
    private var count = 0
    fun tap(now: Long): ComboFeedback {
        require(now >= 0)
        if (lastTap >= 0 && now-lastTap in 0..59) return ComboFeedback(count,lastLevel,false,false)
        if (lastTap < 0 || now < lastTap || now-lastTap > 900) { count = 0; lastLevel = 0 }
        lastTap = now; count = (count+1).coerceAtMost(999)
        val level = if (count >= 6) 2 else if (count >= 3) 1 else 0
        val animate = lastAnimation < 0 || now-lastAnimation >= 600 || (level > lastLevel && now-lastAnimation >= 160)
        if (animate) lastAnimation = now
        lastLevel = level
        return ComboFeedback(count,level,animate,true)
    }
    fun reset() { lastTap = -1; lastAnimation = -1; count = 0; lastLevel = 0 }
}

object SwipeClassifier {
    fun isSwipe(dx: Float, dy: Float, elapsedMillis: Long, density: Float): Boolean {
        if (density <= 0 || elapsedMillis !in 80..220) return false
        val distance = abs(dx)/density
        return distance >= 70 && abs(dx) > abs(dy)*2.5 && distance*1000/elapsedMillis >= 650
    }
}
