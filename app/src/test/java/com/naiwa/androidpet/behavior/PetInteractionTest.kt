package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import org.junit.Assert.*
import org.junit.Test

class PetInteractionTest {
    @Test fun `explicit walk moves once with both automatic switches disabled and then returns idle`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(false); r.interaction.setMovementEnabled(false)
        assertTrue(r.interaction.manualAction(PetAction.WALK))
        r.playback.clock.advanceBy(200)
        assertTrue("Manual walk must actually change position", r.host.moves.isNotEmpty())
        r.playback.clock.advanceBy(60_000)
        assertEquals(PetAction.IDLE, r.playback.animation.currentAction)
        val count = r.host.moves.size
        r.playback.clock.advanceBy(120_000)
        assertEquals(count, r.host.moves.size)
        assertFalse(r.interaction.snapshot.movementEnabled)
    }

    @Test fun `explicit walk never competes with an active drag`() {
        val r = InteractionRig(); r.interaction.onTouchDown()
        assertFalse(r.interaction.manualAction(PetAction.WALK))
        r.playback.clock.advanceBy(1000)
        assertTrue(r.host.moves.isEmpty())
    }

    @Test fun `manual special action stops old movement without being erased by its cleanup`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(false); r.interaction.setMovementEnabled(true)
        r.playback.clock.advanceBy(30300)
        assertTrue(r.interaction.manualAction(PetAction.LAUGH_FALL))
        val moved = r.host.moves.size
        r.playback.clock.fireCancelledJobs(); r.playback.clock.advanceBy(899)
        assertEquals(PetAction.LAUGH_FALL,r.playback.animation.currentAction)
        assertEquals(moved,r.host.moves.size)
        r.playback.clock.advanceBy(1); assertEquals(PetAction.IDLE,r.playback.animation.currentAction)
    }

    @Test fun `a failed automatic decode revokes eligibility and never retries that action`() {
        val pool = approvedPool(); pool.automaticActions.filter { it != PetAction.BLINK }.forEach(pool::disable)
        val r = InteractionRig(pool); r.playback.clock.advanceBy(2999)
        r.playback.sink.failNext = true; r.playback.clock.advanceBy(1)
        assertFalse(pool.automaticActions.contains(PetAction.BLINK))
        assertEquals(1,r.playback.errors.size)
        r.playback.clock.advanceBy(60000)
        assertEquals(1,r.playback.actions.count { it == PetAction.BLINK })
        assertEquals(PetAction.IDLE,r.playback.animation.currentAction)
    }
    @Test fun `frequent ordinary actions do not starve the independent movement interval`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true); r.interaction.setMovementEnabled(true)
        r.playback.clock.advanceBy(30_300)
        assertTrue("completed actions=${r.playback.actions}",r.host.moves.isNotEmpty())
        assertEquals(PetAction.WALK,r.playback.animation.currentAction)
    }

    @Test fun `movement still works with automatic ordinary behavior disabled`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(false); r.interaction.setMovementEnabled(true)
        r.playback.clock.advanceBy(30_300)
        assertTrue(r.host.moves.isNotEmpty()); assertEquals(PetAction.WALK,r.playback.animation.currentAction)
    }

    @Test fun `manual selection pauses automatic scheduling until idle and cooldown`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true)
        r.playback.clock.advanceBy(2900)
        assertTrue(r.interaction.manualAction(PetAction.LAUGH_FALL))
        r.playback.clock.advanceBy(899)
        assertEquals(PetAction.LAUGH_FALL,r.playback.animation.currentAction)
        r.playback.clock.advanceBy(1)
        assertEquals(PetAction.IDLE,r.playback.animation.currentAction)
        r.playback.clock.advanceBy(2999)
        assertEquals(0,r.playback.actions.count { it == PetAction.BLINK })
        r.playback.clock.advanceBy(1)
        assertEquals(PetAction.BLINK,r.playback.animation.currentAction)
    }

    @Test fun `tap uses only small approved actions and cannot interrupt a special action`() {
        val r = InteractionRig()
        assertTrue(r.interaction.onTap()); assertEquals(PetAction.BLINK,r.playback.animation.currentAction)
        r.interaction.manualAction(PetAction.LAUGH_FALL)
        assertFalse(r.interaction.onTap()); assertEquals(PetAction.LAUGH_FALL,r.playback.animation.currentAction)
    }

    @Test fun `drag release delays scheduling and repeated touch does not arm duplicate work`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true)
        r.interaction.onTouchDown(); r.playback.clock.advanceBy(10000)
        r.interaction.onTouchReleased(); r.playback.clock.advanceBy(1499)
        assertEquals(2,r.playback.clock.pending) // One idle frame and one 1500ms release callback.
        r.interaction.onTouchDown(); r.interaction.onTouchReleased()
        r.playback.clock.advanceBy(4499)
        assertEquals(listOf(PetAction.IDLE),r.playback.actions)
        r.playback.clock.advanceBy(1)
        assertEquals(PetAction.BLINK,r.playback.animation.currentAction)
    }

    @Test fun `drag pauses active movement immediately`() {
        val r = InteractionRig(); r.interaction.setMovementEnabled(true)
        r.playback.clock.advanceBy(30_300); assertTrue(r.host.moves.isNotEmpty())
        r.interaction.onTouchDown(); val count = r.host.moves.size
        r.playback.clock.advanceBy(90000); assertEquals(count,r.host.moves.size)
        r.interaction.onTouchReleased(); r.playback.clock.advanceBy(31499)
        assertEquals(count,r.host.moves.size)
    }

    @Test fun `open controls suspends automation but still permits labelled manual previews`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true); r.interaction.setPanelOpen(true)
        r.playback.clock.advanceBy(60_000); assertEquals(listOf(PetAction.IDLE),r.playback.actions)
        assertTrue(r.interaction.manualAction(PetAction.WAVE))
        r.playback.clock.advanceBy(200); assertEquals(PetAction.IDLE,r.playback.animation.currentAction)
    }

    @Test fun `shutdown cancels behavior movement release and animation callbacks`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true); r.interaction.setMovementEnabled(true)
        r.interaction.onTouchDown(); r.interaction.onTouchReleased()
        r.interaction.close(); r.playback.animation.close()
        val count = r.playback.sink.frames.size
        r.playback.clock.fireCancelledJobs(); r.playback.clock.advanceBy(180_000)
        assertEquals(count,r.playback.sink.frames.size); assertTrue(r.host.moves.isEmpty())
        assertEquals(0,r.playback.clock.pending); assertFalse(r.interaction.onTap())
    }

    @Test fun `hidden state cancels release and autonomous work`() {
        val r = InteractionRig(); r.interaction.setAutomaticEnabled(true)
        r.interaction.onTouchDown(); r.interaction.onTouchReleased(); r.interaction.setVisible(false)
        r.playback.animation.pause(); r.playback.clock.advanceBy(180_000)
        assertEquals(listOf(PetAction.IDLE),r.playback.actions); assertEquals(0,r.playback.clock.pending)
    }

    @Test fun `missing shipped style assets leave tap and auto pool unavailable`() {
        val r = InteractionRig(ActionPool(emptyMap(),emptyMap()))
        r.interaction.setAutomaticEnabled(true); r.interaction.setMovementEnabled(true)
        assertFalse(r.interaction.onTap()); r.playback.clock.advanceBy(180_000)
        assertTrue(r.host.moves.isEmpty()); assertEquals(listOf(PetAction.IDLE),r.playback.actions)
    }
}

private class InteractionRig(pool: ActionPool = approvedPool()) {
    val playback = PlaybackRig()
    val host = MemoryMoveHost()
    val interaction = PetInteractionController(playback.animation,playback.clock,FixedRandom(0),pool,
        BehaviorSettings(actionMinMillis = 3000,actionMaxMillis = 3000,moveMinMillis = 30000,moveMaxMillis = 30000),host,{40.0})
    init {
        playback.animation.actionListener = { playback.actions.add(it); interaction.onActionChanged() }
        playback.animation.failureListener = interaction::onActionFailed
        playback.animation.start(); interaction.setVisible(true)
    }
}
