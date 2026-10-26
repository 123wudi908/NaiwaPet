package com.naiwa.androidpet.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.runtime.PetDiagnostics

/** Short, locally synthesized sounds; no sampled character audio or network access. */
class FeedbackPlayer(context: Context) {
    private val preferences = PetPreferences(context)
    private var tone: ToneGenerator? = null
    fun play(good: Boolean = true) {
        if (!preferences.soundEnabled) return
        try {
            val player = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC,25).also { tone = it }
            player.startTone(if (good) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK,45)
        } catch (error: RuntimeException) { PetDiagnostics.event("sound.unavailable",error = error) }
    }
    fun close() { tone?.release(); tone = null }
}
