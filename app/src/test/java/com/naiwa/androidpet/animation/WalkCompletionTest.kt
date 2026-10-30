package com.naiwa.androidpet.animation

import com.naiwa.androidpet.behavior.*
import com.naiwa.androidpet.core.*
import org.junit.Assert.*
import org.junit.Test

class WalkCompletionTest {
    @Test fun `1400 ms stop completes the second sixth frame at 1500 without another cycle`() {
        val r = Rig(); r.start(); r.clock.advanceBy(1400)
        assertEquals("walk/6", r.sink.path)
        r.player.finishMovement()
        assertEquals(PetAction.WALK, r.player.currentAction)
        r.clock.advanceBy(99); assertEquals("walk/6", r.sink.path)
        r.clock.advanceBy(1); assertEquals("idle/1", r.sink.path)
        assertEquals(12, r.sink.frames.count { it.second.startsWith("walk/") })
        assertEquals(1500L, r.sink.frames.last().first)
    }

    @Test fun `stop completes only the current frame instead of the entire cycle`() {
        val r = Rig(); r.start(); r.clock.advanceBy(200); r.player.finishMovement()
        assertEquals("walk/2", r.sink.path)
        r.clock.advanceBy(49); assertEquals(PetAction.WALK, r.player.currentAction)
        r.clock.advanceBy(1); assertEquals(PetAction.IDLE, r.player.currentAction)
        assertEquals(2, r.sink.frames.count { it.second.startsWith("walk/") })
    }

    @Test fun `repeated stop requests never postpone the same frame boundary`() {
        val r = Rig(); r.start(); r.clock.advanceBy(200); r.player.finishMovement()
        r.clock.advanceBy(40); r.player.finishMovement(); r.player.finishMovement()
        assertEquals("walk/2", r.sink.path)
        r.clock.advanceBy(10); assertEquals(PetAction.IDLE, r.player.currentAction)
        r.clock.advanceBy(2000); assertEquals(2, r.sink.frames.count { it.second.startsWith("walk/") })
    }

    @Test fun `stop during a pending decode keeps the completed visible frame and ignores late pixels`() {
        val r = Rig(); r.start(); r.sink.hold = true; r.clock.advanceBy(125)
        val stale = checkNotNull(r.sink.pending)
        r.clock.advanceBy(25); r.player.finishMovement()
        assertEquals(PetAction.IDLE, r.player.currentAction)
        stale(Result.success(Unit)); r.clock.advanceBy(1000)
        assertEquals(PetAction.IDLE, r.player.currentAction)
        assertEquals(1, r.sink.frames.count { it.second.startsWith("walk/") })
    }

    @Test fun `slow decoded walk frame receives its full visible time before stopping`() {
        val r = Rig(); r.start(); r.sink.hold = true; r.clock.advanceBy(125)
        r.clock.advanceBy(10); r.sink.release()
        r.clock.advanceBy(25); r.player.finishMovement()
        r.clock.advanceBy(99); assertEquals("walk/2", r.sink.path)
        r.clock.advanceBy(1); assertEquals("idle/1", r.sink.path)
        assertEquals(260L, r.sink.frames.last().first)
    }

    @Test fun `manual action replaces a pending walk finish and old callbacks cannot return idle`() {
        val r = Rig(); r.start(); r.clock.advanceBy(40); r.player.finishMovement()
        assertTrue(r.player.request(PetAction.WAVE, ActionOrigin.MANUAL))
        r.clock.advanceBy(100); assertEquals(PetAction.WAVE, r.player.currentAction)
        assertEquals("wave/1", r.sink.path)
    }

    @Test fun `paused and reduced motion walks cannot wait forever for a nonexistent timer`() {
        val paused = Rig(); paused.start(); paused.player.pause(); paused.player.finishMovement()
        assertEquals(PetAction.IDLE, paused.player.currentAction); assertTrue(paused.player.isPaused)
        val count = paused.sink.frames.size; paused.clock.advanceBy(1000); assertEquals(count, paused.sink.frames.size)
        val static = Rig(); static.start(); static.player.setMotionEnabled(false); static.player.finishMovement()
        assertEquals(PetAction.IDLE, static.player.currentAction)
        static.clock.advanceBy(1000); assertEquals("idle/1", static.sink.path)
    }

    @Test fun `closing during pending finish leaves no later frame delivery`() {
        val r = Rig(); r.start(); r.clock.advanceBy(40); r.player.finishMovement(); r.player.close()
        val count = r.sink.frames.size; r.clock.advanceBy(1000)
        assertEquals(count, r.sink.frames.size); assertTrue(r.player.isPaused)
    }

    @Test fun `resize or visibility pause during stopping does not restart the walk on resume`() {
        val r = Rig(); r.start(); r.clock.advanceBy(40); r.player.finishMovement(); r.player.pause()
        r.clock.advanceBy(1000); r.player.resume()
        assertEquals(PetAction.IDLE,r.player.currentAction)
        assertEquals("idle/1",r.sink.path)
    }

    @Test fun `physical movement keeps its original deadline and position during visual completion`() {
        val r = Rig(); r.player.start()
        val host = object : HorizontalMoveHost {
            override val area = ScreenArea(10,20,110,220)
            override val size = PetSize(20,40)
            override var position = Position(50,60)
            var moves = 0; var saved = 0
            override fun moveTo(position: Position) { this.position = position; moves++ }
            override fun savePosition() { saved++ }
        }
        val movement = AutoMoveController(r.clock, BehaviorRandom { 0 }, host, BehaviorSettings(), { true },
            { r.player.request(PetAction.WALK, ActionOrigin.MANUAL_MOVEMENT) }, r.player::finishMovement, { 40.0 })
        movement.setSuspended(false); assertTrue(movement.moveOnce())
        r.clock.advanceBy(1000)
        assertFalse(movement.isMoving); assertEquals(Position(10,60), host.position); assertEquals(1, host.saved)
        val count = host.moves
        r.clock.advanceBy(125)
        assertEquals(count, host.moves); assertEquals(PetAction.IDLE, r.player.currentAction)
        movement.close()
    }

    @Test fun `preview stop obeys the same frame boundary and cannot stop a different owner`() {
        val r = Rig(); r.player.start(); r.player.request(PetAction.WALK,ActionOrigin.GAME)
        r.clock.advanceBy(1400); r.player.finishMovement()
        assertEquals("walk/6",r.sink.path)
        r.player.finishPreviewWalk(); r.clock.advanceBy(99)
        assertEquals(PetAction.WALK,r.player.currentAction)
        r.clock.advanceBy(1); assertEquals(PetAction.IDLE,r.player.currentAction)
        r.player.request(PetAction.WAVE,ActionOrigin.MANUAL); r.player.finishPreviewWalk()
        assertEquals(PetAction.WAVE,r.player.currentAction)
        r.player.request(PetAction.WALK,ActionOrigin.MANUAL_MOVEMENT); r.player.finishPreviewWalk()
        r.clock.advanceBy(250); assertEquals(PetAction.WALK,r.player.currentAction)
    }

    private class Rig {
        val clock = BehaviorClock()
        val sink = Sink(clock)
        val player = AnimationController(mapOf(
            PetAction.IDLE to AnimationClip(PetAction.IDLE,listOf("idle/1"),2,true),
            PetAction.WALK to AnimationClip(PetAction.WALK,(1..6).map { "walk/$it" },8,true,
                frameDurationsMillis=List(6){125}),
            PetAction.WAVE to AnimationClip(PetAction.WAVE,listOf("wave/1"),1,false),
        ),clock,sink){fail(it)}
        fun start(){ player.start(); check(player.request(PetAction.WALK,ActionOrigin.MOVEMENT)) }
    }
    private class Sink(private val clock: FrameScheduler) : FrameSink {
        val frames = mutableListOf<Pair<Long,String>>()
        var path: String? = null
        var hold = false
        var pending: ((Result<Unit>) -> Unit)? = null
        private var generation = 0L
        override fun present(path: String, completion: (Result<Unit>) -> Unit) {
            val ticket = ++generation
            if(hold){hold=false; pending={result->if(result.isSuccess && ticket==generation){this.path=path;frames.add(clock.nowMillis to path)};completion(result)};return}
            this.path=path;frames.add(clock.nowMillis to path);completion(Result.success(Unit))
        }
        fun release(){checkNotNull(pending)(Result.success(Unit));pending=null}
        override fun cancelPending(){generation++}
        override fun clear()=Unit
        override fun close()=Unit
    }
}
