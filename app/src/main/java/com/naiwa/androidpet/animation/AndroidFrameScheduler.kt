package com.naiwa.androidpet.animation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class AndroidFrameScheduler : FrameScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override val nowMillis: Long get() = SystemClock.uptimeMillis()
    override fun post(delayMillis: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMillis)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
