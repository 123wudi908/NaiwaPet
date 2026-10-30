package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.core.*
import org.junit.Assert.*
import org.junit.Test

class AutoMoveTest {
    @Test fun `closing from inside a position update cannot enqueue another movement tick`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        r.host.afterMove = { r.move.close(); r.playback.animation.close() }
        r.playback.clock.advanceBy(30016)
        assertEquals(0,r.playback.clock.pending)
    }
    @Test fun `queued walk waits 30 seconds then moves horizontally within safe bounds`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        assertTrue(r.move.queueMovement())
        r.playback.clock.advanceBy(29999); assertTrue(r.host.moves.isEmpty())
        r.playback.clock.advanceBy(1); assertEquals(PetAction.WALK, r.playback.animation.currentAction)
        r.playback.clock.advanceBy(1000)
        assertEquals(Position(10,60), r.host.position)
        assertTrue(r.host.moves.all { it.y == 60 && it.x in 10..90 })
        assertFalse(r.move.isMoving); assertEquals(1, r.host.saved)
        val movesAtDeadline = r.host.moves.size
        r.playback.clock.advanceBy(100)
        assertEquals(movesAtDeadline, r.host.moves.size)
        assertEquals(PetAction.IDLE, r.playback.animation.currentAction)
    }

    @Test fun `suspending during drag immediately cancels movement and old ticks`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        r.playback.clock.advanceBy(30_400); assertTrue(r.host.moves.isNotEmpty())
        r.move.setSuspended(true)
        val count = r.host.moves.size
        r.playback.clock.fireCancelledJobs(); r.playback.clock.advanceBy(120_000)
        assertEquals(count, r.host.moves.size); assertFalse(r.move.isMoving)
        assertEquals(PetAction.IDLE, r.playback.animation.currentAction)
    }

    @Test fun `missing walk prevents scheduling and never changes position`() {
        val r = MoveRig(walk = false); r.move.setEnabled(true); r.move.setSuspended(false)
        assertFalse(r.move.queueMovement()); assertEquals(1, r.playback.clock.pending)
        r.playback.clock.advanceBy(180_000)
        assertTrue(r.host.moves.isEmpty()); assertFalse(r.move.isMoving)
    }

    @Test fun `movement can be independently disabled during a walk`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        r.playback.clock.advanceBy(30_300); r.move.setEnabled(false)
        val count = r.host.moves.size; r.playback.clock.advanceBy(180_000)
        assertEquals(count,r.host.moves.size); assertEquals(PetAction.IDLE,r.playback.animation.currentAction)
    }

    @Test fun `repeated enable does not create overlapping movement tasks`() {
        val r = MoveRig(); repeat(8) { r.move.setEnabled(true); r.move.setSuspended(false) }
        r.playback.clock.advanceBy(31_000)
        assertEquals(1,r.playback.actions.count { it == PetAction.WALK })
        assertEquals(1,r.host.saved)
    }

    @Test fun `closed movement ignores already delivered cancelled callbacks`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        r.playback.clock.advanceBy(30_300); r.move.close()
        val count = r.host.moves.size
        r.playback.clock.fireCancelledJobs(); r.playback.clock.advanceBy(180_000)
        assertEquals(count,r.host.moves.size); assertFalse(r.move.queueMovement())
    }

    @Test fun `shrinking safe area while walking cannot move pet outside it`() {
        val r = MoveRig(); r.move.setEnabled(true); r.move.setSuspended(false)
        r.playback.clock.advanceBy(30_200)
        r.host.area = ScreenArea(30,20,70,220)
        r.playback.clock.advanceBy(1000)
        assertTrue(r.host.position.x in 30..50); assertEquals(60,r.host.position.y)
    }
}

internal class MemoryMoveHost : HorizontalMoveHost {
    override var area = ScreenArea(10,20,110,220)
    override var size = PetSize(20,40)
    override var position = Position(50,60)
    val moves = mutableListOf<Position>()
    var saved = 0
    var afterMove: (() -> Unit)? = null
    override fun moveTo(position: Position) { this.position = position; moves.add(position); afterMove?.invoke() }
    override fun savePosition() { saved++ }
}

private class MoveRig(walk: Boolean = true) {
    val playback = PlaybackRig().also { it.animation.start() }
    val host = MemoryMoveHost()
    val move = AutoMoveController(playback.clock, FixedRandom(0), host,
        BehaviorSettings(moveMinMillis = 30_000,moveMaxMillis = 30_000), { walk },
        { playback.animation.request(PetAction.WALK,ActionOrigin.MOVEMENT) },
        { playback.animation.finishMovement() }, { 40.0 })
}
