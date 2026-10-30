package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import org.junit.Assert.*
import org.junit.Test

class ActionPriorityTest {
    @Test fun `game feedback outranks ordinary tap while explicit manual action stays first`() {
        val r = PlaybackRig(); r.animation.start()
        assertTrue(r.animation.request(PetAction.HAPPY,ActionOrigin.GAME))
        assertFalse(r.animation.request(PetAction.BLINK,ActionOrigin.TAP))
        assertTrue(r.animation.request(PetAction.WAVE,ActionOrigin.MANUAL))
        assertFalse(r.animation.request(PetAction.HAPPY,ActionOrigin.GAME))
    }
    @Test fun `ordinary automatic request cannot interrupt special final pose`() {
        val r = PlaybackRig(); r.animation.start()
        assertTrue(r.animation.request(PetAction.LAUGH_FALL, ActionOrigin.MANUAL))
        r.clock.advanceBy(100)
        assertFalse(r.animation.request(PetAction.BLINK, ActionOrigin.AUTOMATIC))
        assertFalse(r.animation.request(PetAction.WALK, ActionOrigin.MOVEMENT))
        assertFalse(r.animation.request(PetAction.WAVE, ActionOrigin.TAP))
        r.clock.advanceBy(799)
        assertEquals(PetAction.LAUGH_FALL, r.animation.currentAction)
        r.clock.advanceBy(1)
        assertEquals(PetAction.IDLE, r.animation.currentAction)
    }

    @Test fun `manual action preempts automatic action and stale timer cannot restore old action`() {
        val r = PlaybackRig(); r.animation.start()
        assertTrue(r.animation.request(PetAction.HAPPY, ActionOrigin.AUTOMATIC))
        assertTrue(r.animation.request(PetAction.WAVE, ActionOrigin.MANUAL))
        assertEquals(ActionOrigin.MANUAL, r.animation.currentOrigin)
        assertFalse(r.animation.request(PetAction.BLINK, ActionOrigin.AUTOMATIC))
        r.clock.advanceBy(200)
        assertEquals(PetAction.IDLE, r.animation.currentAction)
        assertEquals(listOf(PetAction.IDLE, PetAction.HAPPY, PetAction.WAVE, PetAction.IDLE), r.actions)
    }

    @Test fun `walk loops only for movement owner and stop cannot erase newer manual action`() {
        val r = PlaybackRig(); r.animation.start()
        assertTrue(r.animation.request(PetAction.WALK, ActionOrigin.MOVEMENT))
        r.clock.advanceBy(550)
        assertEquals(PetAction.WALK, r.animation.currentAction)
        r.animation.finishMovement()
        assertEquals(PetAction.WALK, r.animation.currentAction)
        r.clock.advanceBy(50)
        assertEquals(PetAction.IDLE, r.animation.currentAction)
        r.animation.request(PetAction.WAVE, ActionOrigin.MANUAL)
        r.animation.finishMovement()
        assertEquals(PetAction.WAVE, r.animation.currentAction)
    }

    @Test fun `hidden or closed animation rejects autonomous requests`() {
        val r = PlaybackRig(); r.animation.start(); r.animation.pause()
        assertFalse(r.animation.request(PetAction.BLINK, ActionOrigin.AUTOMATIC))
        assertFalse(r.animation.request(PetAction.WALK, ActionOrigin.MOVEMENT))
        r.animation.close()
        assertFalse(r.animation.request(PetAction.HAPPY, ActionOrigin.MANUAL))
    }
}

internal class PlaybackRig {
    val clock = BehaviorClock()
    val sink = BehaviorSink()
    val actions = mutableListOf<PetAction>()
    val errors = mutableListOf<String>()
    val animation = AnimationController(PetAction.entries.associateWith { action ->
        AnimationClip(action, listOf("${action.directory}/0.png", "${action.directory}/1.png"), 10,
            action == PetAction.IDLE || action == PetAction.WALK, if (action == PetAction.LAUGH_FALL) 800 else 0)
    }, clock, sink) { errors.add(it) }.also { it.actionListener = { action -> actions.add(action) } }
}

internal class BehaviorSink : FrameSink {
    val frames = mutableListOf<String>()
    var failNext = false
    var closed = false
    override fun present(path: String, completion: (Result<Unit>) -> Unit) {
        frames.add(path)
        if (failNext) { failNext = false; completion(Result.failure(IllegalArgumentException("bad PNG"))) }
        else completion(Result.success(Unit))
    }
    override fun clear() = Unit
    override fun close() { closed = true }
}

internal class BehaviorClock : FrameScheduler {
    override var nowMillis = 0L
    private data class Job(val due: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private val jobs = mutableListOf<Job>()
    val pending: Int get() = jobs.count { !it.cancelled }
    fun fireCancelledJobs() {
        val cancelled = jobs.filter { it.cancelled }; jobs.removeAll(cancelled.toSet())
        cancelled.forEach { it.block() }
    }
    override fun post(delayMillis: Long, action: () -> Unit): Cancellable {
        require(delayMillis >= 0)
        val job = Job(nowMillis + delayMillis, action); jobs.add(job)
        return Cancellable { job.cancelled = true }
    }
    fun advanceBy(millis: Long) {
        val end = nowMillis + millis; var budget = 100000
        while (true) {
            val job = jobs.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
            check(budget-- > 0) { "duplicate or unbounded scheduling" }
            jobs.remove(job); nowMillis = job.due; job.block()
        }
        nowMillis = end
    }
}
