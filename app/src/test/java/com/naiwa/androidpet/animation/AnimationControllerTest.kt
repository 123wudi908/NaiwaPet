package com.naiwa.androidpet.animation

import org.junit.Assert.*
import org.junit.Test

class AnimationControllerTest {
    @Test fun `reduced motion retains a static pet and still completes a one shot action`() {
        val r = Rig(); r.controller.start(); r.controller.setMotionEnabled(false)
        val frames = r.sink.paths.size
        r.clock.advanceBy(10_000)
        assertEquals(frames,r.sink.paths.size)
        r.controller.play(PetAction.HAPPY); r.clock.advanceBy(250)
        assertEquals(PetAction.IDLE,r.controller.currentAction)
        assertNotNull(r.sink.currentPath)
    }
    @Test fun `one failed idle frame cannot erase the already visible pet`() {
        val r = Rig(); r.controller.start()
        r.sink.failPath = "naiwa/idle/frame2.png"
        r.clock.advanceBy(100)
        assertNotNull("A bad idle frame must keep the last healthy pet visible", r.sink.currentPath)
        r.clock.advanceBy(60_000)
        assertEquals("naiwa/idle/frame1.png", r.sink.currentPath)
        assertFalse(r.controller.isPaused)
        assertEquals(1, r.errors.size)
    }

    @Test fun `all failed idle frames stop decoding but retain last healthy content`() {
        val r = Rig(); r.controller.start()
        r.sink.failAll = true
        r.clock.advanceBy(300)
        assertTrue(r.controller.isPaused)
        assertEquals("naiwa/idle/frame1.png", r.sink.currentPath)
        val attempts = r.sink.paths.size
        r.clock.advanceBy(600_000)
        assertEquals(attempts, r.sink.paths.size)
    }

    @Test fun `per frame timing preserves a short anticipation and a longer expression hold`() {
        val r = Rig()
        val clip = AnimationClip(PetAction.WAVE, listOf("wave/0.png", "wave/1.png"), 10, false,
            frameDurationsMillis = listOf(60, 240), interruptible = false)
        val controller = AnimationController(r.clips + (PetAction.WAVE to clip), r.clock, r.sink) { r.errors.add(it) }
        controller.start(); controller.play(PetAction.WAVE)
        r.clock.advanceBy(59); assertEquals("wave/0.png", r.sink.currentPath)
        assertFalse(controller.request(PetAction.BLINK, ActionOrigin.TAP))
        r.clock.advanceBy(1); assertEquals("wave/1.png", r.sink.currentPath)
        r.clock.advanceBy(239); assertEquals(PetAction.WAVE, controller.currentAction)
        r.clock.advanceBy(1); assertEquals(PetAction.IDLE, controller.currentAction)
    }

    @Test fun `action listener follows explicit switch and automatic idle fallback`() {
        val r = Rig(); val actions = mutableListOf<PetAction>()
        r.controller.actionListener = { actions.add(it) }
        r.controller.start(); r.controller.play(PetAction.WAVE); r.clock.advanceBy(200)
        assertEquals(listOf(PetAction.IDLE, PetAction.WAVE, PetAction.IDLE), actions)
    }

    @Test fun `cancelled decode and pause cannot deliver stale action notifications`() {
        val r = Rig(); val actions = mutableListOf<PetAction>()
        r.controller.actionListener = { actions.add(it) }
        r.controller.start(); r.sink.holdNext = true; r.controller.play(PetAction.WAVE)
        val old = checkNotNull(r.sink.held)
        r.controller.play(PetAction.HAPPY); r.controller.pause()
        old(Result.success(Unit)); r.clock.advanceBy(1000)
        assertEquals(listOf(PetAction.IDLE, PetAction.WAVE, PetAction.HAPPY), actions)
        r.controller.actionListener = null; r.controller.resume(); r.clock.advanceBy(1000)
        assertEquals(3, actions.size)
        r.controller.actionListener = { actions.add(it) }; r.controller.close()
        assertNull(r.controller.actionListener)
    }
    private fun clip(action: PetAction, fps: Int = 10, loop: Boolean = false) =
        AnimationClip(action, listOf("naiwa/${action.directory}/frame1.png", "naiwa/${action.directory}/frame2.png"), fps, loop)

    private class Rig {
        val clock = VirtualScheduler()
        val sink = RecordingSink()
        val errors = mutableListOf<String>()
        val clips = PetAction.entries.associateWith { action ->
            AnimationClip(action, listOf("naiwa/${action.directory}/frame1.png", "naiwa/${action.directory}/frame2.png"), 10, action == PetAction.IDLE)
        }
        val controller = AnimationController(clips, clock, sink) { errors.add(it) }
    }

    @Test fun `idle loops at the configured frame interval`() {
        val r = Rig(); r.controller.start()
        r.clock.advanceBy(99)
        assertEquals(listOf("naiwa/idle/frame1.png"), r.sink.paths)
        r.clock.advanceBy(1)
        assertEquals("naiwa/idle/frame2.png", r.sink.currentPath)
        r.clock.advanceBy(100)
        assertEquals("naiwa/idle/frame1.png", r.sink.currentPath)
    }

    @Test fun `single action displays every frame then returns to idle`() {
        val r = Rig(); r.controller.start(); r.controller.play(PetAction.WAVE)
        r.clock.advanceBy(200)
        assertEquals(listOf("naiwa/idle/frame1.png", "naiwa/wave/frame1.png", "naiwa/wave/frame2.png", "naiwa/idle/frame1.png"), r.sink.paths)
        assertEquals(PetAction.IDLE, r.controller.currentAction)
    }

    @Test fun `pause retains display and resume preserves frame index`() {
        val r = Rig(); r.controller.start(); r.clock.advanceBy(100)
        r.controller.pause()
        assertEquals("naiwa/idle/frame2.png", r.sink.currentPath)
        val count = r.sink.paths.size
        r.clock.advanceBy(60000)
        assertEquals(count, r.sink.paths.size)
        r.controller.resume()
        assertEquals("naiwa/idle/frame2.png", r.sink.currentPath)
        r.clock.advanceBy(100)
        assertEquals("naiwa/idle/frame1.png", r.sink.currentPath)
    }

    @Test fun `close prevents further decoding and cannot be resumed`() {
        val r = Rig(); r.controller.start(); r.controller.close(); r.controller.close()
        val count = r.sink.paths.size
        r.clock.advanceBy(10000); r.controller.resume()
        assertFalse(r.controller.play(PetAction.HAPPY))
        assertEquals(count, r.sink.paths.size)
        assertNull(r.sink.currentPath)
        assertTrue(r.sink.closed)
    }

    @Test fun `missing action is diagnosed without interrupting idle`() {
        val clock = VirtualScheduler(); val sink = RecordingSink(); val errors = mutableListOf<String>()
        val controller = AnimationController(mapOf(PetAction.IDLE to clip(PetAction.IDLE, loop = true)), clock, sink) { errors.add(it) }
        controller.start()
        assertFalse(controller.play(PetAction.BLINK))
        assertTrue(errors.single().contains("naiwa/blink"))
        clock.advanceBy(100)
        assertEquals("naiwa/idle/frame2.png", sink.currentPath)
    }

    @Test fun `decode error reports path and falls back to idle`() {
        val r = Rig(); r.controller.start()
        r.sink.failPath = "naiwa/happy/frame1.png"
        r.controller.play(PetAction.HAPPY)
        assertEquals(PetAction.IDLE, r.controller.currentAction)
        assertTrue(r.errors.single().contains("naiwa/happy/frame1.png"))
    }

    @Test fun `failed idle pauses instead of retrying forever`() {
        val r = Rig(); r.sink.failAll = true; r.controller.start()
        r.clock.advanceBy(100000)
        assertEquals(2, r.sink.paths.size)
        assertTrue(r.controller.isPaused)
        assertNull(r.sink.currentPath)
    }

    @Test fun `late completion of old action cannot reschedule it`() {
        val r = Rig(); r.controller.start()
        r.sink.holdNext = true
        r.controller.play(PetAction.WAVE)
        val old = checkNotNull(r.sink.held)
        r.controller.play(PetAction.HAPPY)
        old(Result.success(Unit))
        r.clock.advanceBy(100)
        assertEquals(PetAction.HAPPY, r.controller.currentAction)
        assertEquals("naiwa/happy/frame2.png", r.sink.currentPath)
    }

    @Test fun `empty clips and invalid fps are rejected before playback`() {
        assertThrows(IllegalArgumentException::class.java) { AnimationClip(PetAction.IDLE, emptyList(), 10, true) }
        assertThrows(IllegalArgumentException::class.java) { clip(PetAction.IDLE, fps = 0) }
        assertThrows(IllegalArgumentException::class.java) { clip(PetAction.IDLE, fps = 1000) }
    }

    @Test fun `non looping idle holds its last frame instead of silently looping`() {
        val clock = VirtualScheduler(); val sink = RecordingSink()
        val controller = AnimationController(mapOf(PetAction.IDLE to clip(PetAction.IDLE, loop = false)), clock, sink) { fail(it) }
        controller.start(); clock.advanceBy(2000)
        assertEquals(2, sink.paths.size)
        assertEquals("naiwa/idle/frame2.png", sink.currentPath)
    }

    @Test fun `laugh fall plays 73 frames at 8 fps and holds the last for 800 ms`() {
        val clock = VirtualScheduler(); val sink = RecordingSink()
        val laugh = AnimationClip(PetAction.LAUGH_FALL,
            (0..72).map { "naiwa/laugh_fall/laugh_fall_%03d.png".format(it) },
            8, false, lastFrameHoldMillis = 800)
        val controller = AnimationController(mapOf(
            PetAction.IDLE to clip(PetAction.IDLE, loop = true), PetAction.LAUGH_FALL to laugh,
        ), clock, sink) { fail(it) }
        controller.play(PetAction.LAUGH_FALL)
        clock.advanceBy(8999)
        assertEquals("naiwa/laugh_fall/laugh_fall_071.png", sink.currentPath)
        clock.advanceBy(1)
        assertEquals("naiwa/laugh_fall/laugh_fall_072.png", sink.currentPath)
        clock.advanceBy(799)
        assertEquals(73, sink.paths.size)
        assertEquals(PetAction.LAUGH_FALL, controller.currentAction)
        clock.advanceBy(1)
        assertEquals(PetAction.IDLE, controller.currentAction)
        assertEquals("naiwa/idle/frame1.png", sink.currentPath)
    }

    @Test fun `another action cancels the pending return during final hold`() {
        val clock = VirtualScheduler(); val sink = RecordingSink()
        val laugh = AnimationClip(PetAction.LAUGH_FALL,
            listOf("naiwa/laugh_fall/first.png", "naiwa/laugh_fall/last.png"), 8, false, 800)
        val controller = AnimationController(mapOf(
            PetAction.IDLE to clip(PetAction.IDLE, loop = true), PetAction.LAUGH_FALL to laugh,
            PetAction.WAVE to clip(PetAction.WAVE, loop = true),
        ), clock, sink) { fail(it) }
        controller.play(PetAction.LAUGH_FALL); clock.advanceBy(125)
        controller.play(PetAction.WAVE); clock.advanceBy(1000)
        assertEquals(PetAction.WAVE, controller.currentAction)
        assertTrue(sink.currentPath!!.startsWith("naiwa/wave/"))
    }

    @Test fun `final hold starts after the decoded frame is presented`() {
        val clock = VirtualScheduler(); val sink = RecordingSink()
        val laugh = AnimationClip(PetAction.LAUGH_FALL,
            listOf("naiwa/laugh_fall/first.png", "naiwa/laugh_fall/last.png"), 8, false, 800)
        val controller = AnimationController(mapOf(
            PetAction.IDLE to clip(PetAction.IDLE, loop = true), PetAction.LAUGH_FALL to laugh,
        ), clock, sink) { fail(it) }
        controller.play(PetAction.LAUGH_FALL)
        sink.holdNext = true; clock.advanceBy(125)
        val completeDecode = checkNotNull(sink.held)
        clock.advanceBy(500); completeDecode(Result.success(Unit))
        clock.advanceBy(799)
        assertEquals(PetAction.LAUGH_FALL, controller.currentAction)
        clock.advanceBy(1)
        assertEquals(PetAction.IDLE, controller.currentAction)
    }

    @Test fun `pause cancels final hold and resume restores the final pose`() {
        val clock = VirtualScheduler(); val sink = RecordingSink()
        val laugh = AnimationClip(PetAction.LAUGH_FALL,
            listOf("naiwa/laugh_fall/first.png", "naiwa/laugh_fall/last.png"), 8, false, 800)
        val controller = AnimationController(mapOf(
            PetAction.IDLE to clip(PetAction.IDLE, loop = true), PetAction.LAUGH_FALL to laugh,
        ), clock, sink) { fail(it) }
        controller.play(PetAction.LAUGH_FALL); clock.advanceBy(400)
        controller.pause(); val count = sink.paths.size
        clock.advanceBy(10000)
        assertEquals(count, sink.paths.size)
        assertEquals("naiwa/laugh_fall/last.png", sink.currentPath)
        controller.resume()
        assertEquals("naiwa/laugh_fall/last.png", sink.currentPath)
        clock.advanceBy(799)
        assertEquals(PetAction.LAUGH_FALL, controller.currentAction)
        clock.advanceBy(1)
        assertEquals(PetAction.IDLE, controller.currentAction)
    }

    @Test fun `invalid final holds are rejected before playback`() {
        val frames = listOf("naiwa/laugh_fall/last.png")
        assertThrows(IllegalArgumentException::class.java) { AnimationClip(PetAction.LAUGH_FALL, frames, 8, false, -1) }
        assertThrows(IllegalArgumentException::class.java) { AnimationClip(PetAction.LAUGH_FALL, frames, 8, false, 10001) }
        assertThrows(IllegalArgumentException::class.java) { AnimationClip(PetAction.LAUGH_FALL, frames, 8, true, 800) }
    }

    private class RecordingSink : FrameSink {
        val paths = mutableListOf<String>()
        var currentPath: String? = null
        var failPath: String? = null
        var failAll = false
        var holdNext = false
        var held: ((Result<Unit>) -> Unit)? = null
        var closed = false
        override fun present(path: String, completion: (Result<Unit>) -> Unit) {
            paths.add(path)
            if (holdNext) { holdNext = false; held = completion; return }
            if (failAll || path == failPath) completion(Result.failure(IllegalArgumentException("Invalid PNG: $path")))
            else { currentPath = path; completion(Result.success(Unit)) }
        }
        override fun clear() { currentPath = null }
        override fun close() { clear(); closed = true }
    }

    private class VirtualScheduler : FrameScheduler {
        override var nowMillis = 0L
        private data class Job(val time: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override fun post(delayMillis: Long, action: () -> Unit): Cancellable {
            val job = Job(nowMillis + delayMillis, action)
            jobs.add(job)
            return Cancellable { job.cancelled = true }
        }
        fun advanceBy(milliseconds: Long) {
            val end = nowMillis + milliseconds
            var budget = 10000
            while (true) {
                val job = jobs.filter { !it.cancelled && it.time <= end }.minByOrNull { it.time } ?: break
                check(budget-- > 0) { "Unbounded animation scheduling" }
                jobs.remove(job); nowMillis = job.time; job.action()
            }
            nowMillis = end
        }
    }
}
