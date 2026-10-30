package com.naiwa.androidpet.game

import kotlin.math.abs

/** One beat timeline for both scoring and the visible ring. No wall clock or saved-state changes. */
object DrumRhythm {
    const val DURATION_MILLIS = 24_000L
    const val FIRST_BEAT_MILLIS = 1_400L
    const val PERFECT_WINDOW_MILLIS = 130L
    const val GOOD_WINDOW_MILLIS = 260L

    fun stageAt(elapsed: Long) = when {
        elapsed < 8_000 -> 1
        elapsed < 16_000 -> 2
        else -> 3
    }

    fun intervalAt(elapsed: Long): Long = when (stageAt(elapsed)) {
        1 -> 900L
        2 -> 800L
        else -> 720L
    }

    val beats: List<Long> = buildList {
        var next = FIRST_BEAT_MILLIS
        // Every beat keeps its complete late-hit window before the fixed game deadline.
        while (next + GOOD_WINDOW_MILLIS <= DURATION_MILLIS) {
            add(next)
            next += intervalAt(next)
        }
    }

    fun nearestBeat(elapsed: Long): Int = beats.indices.minBy { abs(elapsed-beats[it]) }

    data class Cue(val progress: Float, val hittable: Boolean)
    fun cueAt(elapsed: Long): Cue? {
        val index = beats.indexOfFirst { elapsed <= it + GOOD_WINDOW_MILLIS }
        if (index < 0) return null
        val time = beats[index]
        val start = if (index == 0) 0L else beats[index-1]
        return Cue(((elapsed-start).toFloat()/(time-start)).coerceIn(0f,1f),
            abs(elapsed-time) <= GOOD_WINDOW_MILLIS)
    }
}
