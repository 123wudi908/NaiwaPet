package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import org.junit.Assert.*
import org.junit.Test

class AutoBehaviorTest {
    @Test fun `special cooldown skips later decisions until its full duration has passed`() {
        val r = PlaybackRig(); r.animation.start()
        val pool = ActionPool(mapOf(PetAction.BLINK to
            ActionPolicy(PetAction.BLINK, 1, MaterialStatus.FORMAL, true, true, true, cooldownMillis = 12_000)),
            mapOf(PetAction.BLINK to ActionAssetFacts(true, true, false, true)))
        val auto = controller(r, pool)
        auto.setEnabled(true); auto.setSuspended(false)
        r.clock.advanceBy(14_999)
        assertEquals(1, r.actions.count { it == PetAction.BLINK })
        r.clock.advanceBy(1)
        assertEquals(2, r.actions.count { it == PetAction.BLINK })
    }

    private val settings = BehaviorSettings(actionMinMillis = 3000, actionMaxMillis = 3000)
    private fun controller(r: PlaybackRig, pool: ActionPool = approvedPool(), random: BehaviorRandom = FixedRandom(0),
        queueWalk: () -> Boolean = { false }, walkEnabled: () -> Boolean = { false }) =
        AutoBehaviorController(r.clock, random, pool, settings,
            { r.animation.request(it, ActionOrigin.AUTOMATIC) }, queueWalk, walkEnabled)

    @Test fun `decision waits its interval and one shot returns to idle`() {
        val r = PlaybackRig(); r.animation.start(); val auto = controller(r)
        auto.setEnabled(true); auto.setSuspended(false)
        r.clock.advanceBy(2999); assertEquals(listOf(PetAction.IDLE), r.actions)
        r.clock.advanceBy(1); assertEquals(PetAction.BLINK, r.animation.currentAction)
        r.clock.advanceBy(200); assertEquals(PetAction.IDLE, r.animation.currentAction)
    }

    @Test fun `disable cancels decisions including a late cancelled callback`() {
        val r = PlaybackRig(); r.animation.start(); val auto = controller(r)
        auto.setEnabled(true); auto.setSuspended(false); auto.setEnabled(false)
        r.clock.fireCancelledJobs(); r.clock.advanceBy(60_000)
        assertEquals(listOf(PetAction.IDLE), r.actions)
    }

    @Test fun `repeated enable does not duplicate decision timers`() {
        val r = PlaybackRig(); r.animation.start(); val auto = controller(r)
        repeat(8) { auto.setEnabled(true); auto.setSuspended(false) }
        r.clock.advanceBy(6100)
        assertEquals(2, r.actions.count { it == PetAction.BLINK })
    }

    @Test fun `empty pool creates no periodic behavior work`() {
        val r = PlaybackRig(); r.animation.start()
        val auto = controller(r, ActionPool(emptyMap(), emptyMap()))
        auto.setEnabled(true); auto.setSuspended(false)
        assertEquals(1, r.clock.pending) // Only the existing idle frame scheduler.
        r.clock.advanceBy(60_000); assertEquals(listOf(PetAction.IDLE), r.actions)
    }

    @Test fun `walk choice queues a move without playing a stationary walk clip`() {
        val r = PlaybackRig(); r.animation.start(); var queued = 0
        val pool = approvedPool()
        pool.automaticActions.filter { it != PetAction.WALK }.forEach(pool::disable)
        val auto = controller(r, pool, queueWalk = { queued++; true }, walkEnabled = { true })
        auto.setEnabled(true); auto.setSuspended(false); r.clock.advanceBy(3000)
        assertEquals(1, queued); assertEquals(PetAction.IDLE, r.animation.currentAction)
    }

    @Test fun `maximum configured delay is inclusive`() {
        val r = PlaybackRig(); r.animation.start()
        val pool = approvedPool(); pool.automaticActions.filter { it != PetAction.BLINK }.forEach(pool::disable)
        val random = BehaviorRandom { bound -> if (bound == 5001) 5000 else 0 }
        val auto = AutoBehaviorController(r.clock, random, pool, BehaviorSettings(),
            { r.animation.request(it, ActionOrigin.AUTOMATIC) }, { false }, { false })
        auto.setEnabled(true); auto.setSuspended(false)
        r.clock.advanceBy(7999); assertEquals(PetAction.IDLE, r.animation.currentAction)
        r.clock.advanceBy(1); assertEquals(PetAction.BLINK, r.animation.currentAction)
    }
}
