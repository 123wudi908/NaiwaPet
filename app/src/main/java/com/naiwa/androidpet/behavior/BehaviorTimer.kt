package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.Cancellable
import com.naiwa.androidpet.animation.FrameScheduler

/** One owned callback; cancellation also invalidates a callback already dispatched. */
internal class BehaviorTimer(private val scheduler: FrameScheduler) {
    private var epoch = 0L
    private var task: Cancellable? = null
    val pending: Boolean get() = task != null
    fun post(delay: Long, block: () -> Unit) {
        cancel()
        val ticket = epoch
        task = scheduler.post(delay) {
            if (ticket == epoch) { task = null; block() }
        }
    }
    fun cancel() { epoch++; task?.cancel(); task = null }
}
