package com.naiwa.androidpet.animation

import org.junit.Assert.*
import org.junit.Test

class DirectionalAnimationTest {
    private class Clock : FrameScheduler {
        override var nowMillis = 0L
        data class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override fun post(delayMillis: Long, action: () -> Unit): Cancellable {
            val job = Job(nowMillis + delayMillis, action); jobs += job
            return Cancellable { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = nowMillis + ms
            repeat(1000) {
                val job = jobs.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at }
                if (job == null) { nowMillis = end; return }
                jobs.remove(job); nowMillis = job.at; job.task()
            }
            error("Unbounded scheduling")
        }
    }
    private class Sink : FrameSink {
        var path: String? = null
        var fail: String? = null
        var hold = false
        var pending: ((Result<Unit>) -> Unit)? = null
        var presentations = 0
        override fun present(path: String, completion: (Result<Unit>) -> Unit) {
            presentations++
            if (hold) { hold = false; pending = completion; return }
            if (fail == path) completion(Result.failure(IllegalArgumentException("bad direction")))
            else { this.path = path; completion(Result.success(Unit)) }
        }
        override fun clear() { path = null }
        override fun close() { clear() }
    }
    private fun clip(action: PetAction, path: String, loop: Boolean = action in setOf(PetAction.IDLE, PetAction.WALK)) =
        AnimationClip(action, listOf(path), 10, loop)
    private val fronts = PetAction.entries.associateWith { clip(it, "front/${it.directory}.png") }
    private val directions = mapOf(
        PetDirection.LEFT to mapOf(PetAction.IDLE to clip(PetAction.IDLE, "left/idle.png")),
        PetDirection.BACK to mapOf(PetAction.IDLE to clip(PetAction.IDLE, "back/idle.png")))

    @Test fun `direction changes show matching idle without a new action event`() {
        val clock = Clock(); val sink = Sink(); val actions = mutableListOf<PetAction>()
        val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
        player.actionListener = actions::add
        player.start(); player.faceMovement(-20.0, 0.0)
        assertEquals("left/idle.png", sink.path)
        assertEquals(PetDirection.LEFT, player.currentDirection)
        assertEquals(listOf(PetAction.IDLE), actions)
        clock.advance(400)
        assertEquals("left/idle.png", sink.path)
    }

    @Test fun `movement retains walk ownership while showing directional static idle`() {
        val clock = Clock(); val sink = Sink()
        val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
        player.start(); assertTrue(player.request(PetAction.WALK, ActionOrigin.MOVEMENT))
        player.faceMovement(-20.0, 0.0)
        assertEquals("left/idle.png", sink.path)
        assertEquals(PetAction.WALK, player.currentAction)
        assertEquals(ActionOrigin.MOVEMENT, player.currentOrigin)
        player.finishMovement()
        assertEquals(PetAction.IDLE, player.currentAction)
        assertEquals("left/idle.png", sink.path)
    }

    @Test fun `every original front interaction plays then returns to the remembered facing`() {
        for (action in listOf(PetAction.BLINK, PetAction.WAVE, PetAction.HAPPY, PetAction.LAUGH_FALL, PetAction.BELLY_DRUM)) {
            val clock = Clock(); val sink = Sink()
            val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
            player.start(); player.faceMovement(-20.0, 0.0); assertTrue(player.play(action))
            assertEquals("front/${action.directory}.png", sink.path)
            assertEquals(PetDirection.FRONT, player.displayedDirection)
            player.faceMovement(0.0, -20.0)
            assertEquals("front/${action.directory}.png", sink.path)
            clock.advance(100)
            assertEquals("back/idle.png", sink.path)
            assertEquals(PetDirection.BACK, player.displayedDirection)
        }
    }

    @Test fun `future same direction action overrides front fallback`() {
        val clock = Clock(); val sink = Sink()
        val extended = directions + (PetDirection.LEFT to (directions.getValue(PetDirection.LEFT) +
            (PetAction.WAVE to clip(PetAction.WAVE, "left/wave.png"))))
        val player = AnimationController(fronts, clock, sink, extended) { fail(it) }
        player.start(); player.faceMovement(-20.0, 0.0); player.play(PetAction.WAVE)
        assertEquals("left/wave.png", sink.path)
        clock.advance(100); assertEquals("left/idle.png", sink.path)
    }

    @Test fun `missing direction falls back to front without losing its logical heading`() {
        val clock = Clock(); val sink = Sink()
        val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
        player.start(); player.faceMovement(20.0, 0.0)
        assertEquals(PetDirection.RIGHT, player.currentDirection)
        assertEquals(PetDirection.FRONT, player.displayedDirection)
        assertEquals("front/idle.png", sink.path)
    }

    @Test fun `broken direction cannot pause or disable original front idle`() {
        val clock = Clock(); val sink = Sink(); val errors = mutableListOf<String>()
        val player = AnimationController(fronts, clock, sink, directions, errors::add)
        player.failureListener = { fail("Original action was disabled") }
        player.start(); sink.fail = "left/idle.png"; player.faceMovement(-20.0, 0.0)
        assertFalse(player.isPaused); assertEquals("front/idle.png", sink.path)
        assertEquals(PetDirection.FRONT, player.displayedDirection)
        val count = sink.presentations
        clock.advance(1000)
        assertEquals(count + 10, sink.presentations)
        assertEquals(1, errors.size)
    }

    @Test fun `paused heading updates wait for resume and closed players ignore movement`() {
        val clock = Clock(); val sink = Sink()
        val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
        player.start(); player.pause(); val count = sink.presentations
        player.faceMovement(-20.0, 0.0); clock.advance(1000)
        assertEquals(count, sink.presentations)
        player.resume(); assertEquals("left/idle.png", sink.path)
        player.close(); player.faceMovement(0.0, -20.0)
        assertEquals(PetDirection.LEFT, player.currentDirection)
        assertNull(sink.path)
    }

    @Test fun `late old decode cannot change the direction playback timeline`() {
        val clock = Clock(); val sink = Sink()
        val player = AnimationController(fronts, clock, sink, directions) { fail(it) }
        player.start(); sink.hold = true; player.faceMovement(-20.0, 0.0)
        val old = checkNotNull(sink.pending)
        player.faceMovement(0.0, -20.0); old(Result.success(Unit)); clock.advance(100)
        assertEquals("back/idle.png", sink.path)
        assertEquals(PetDirection.BACK, player.displayedDirection)
    }
}
