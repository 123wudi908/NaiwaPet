package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.PetAction
import org.junit.Assert.*
import org.junit.Test

class ActionPolicyTest {
    @Test fun `weighted selection respects each literal boundary including rare laugh`() {
        val pool = approvedPool()
        val cases = listOf(0 to PetAction.BLINK, 39 to PetAction.BLINK, 40 to PetAction.WAVE,
            59 to PetAction.WAVE, 60 to PetAction.HAPPY, 77 to PetAction.HAPPY,
            78 to PetAction.WALK, 92 to PetAction.WALK, 93 to PetAction.LAUGH_FALL,
            94 to PetAction.LAUGH_FALL, 95 to PetAction.WOBBLE, 99 to PetAction.WOBBLE)
        for ((ticket, expected) in cases) assertEquals("ticket=$ticket", expected, pool.choose(FixedRandom(ticket)))
    }

    @Test fun `missing invalid temporary and unapproved frames cannot enter automatic pool`() {
        val policies = weights().mapValues { (action, weight) ->
            ActionPolicy(action, weight, MaterialStatus.FORMAL, true, true, true)
        }.toMutableMap()
        policies[PetAction.HAPPY] = policies.getValue(PetAction.HAPPY).copy(materialStatus = MaterialStatus.TEMPORARY)
        policies[PetAction.WALK] = policies.getValue(PetAction.WALK).copy(qualityApproved = false)
        policies[PetAction.WOBBLE] = policies.getValue(PetAction.WOBBLE).copy(allowAutomatic = false)
        val facts = weights().keys.associateWith { ActionAssetFacts(true, true, false, true) }.toMutableMap()
        facts[PetAction.BLINK] = ActionAssetFacts(false, false, false)
        facts[PetAction.WAVE] = ActionAssetFacts(true, false, false)
        facts[PetAction.LAUGH_FALL] = ActionAssetFacts(true, true, true)
        val pool = ActionPool(policies, facts)
        assertTrue(pool.automaticActions.isEmpty())
        assertNull(pool.choose(FixedRandom(0)))
    }

    @Test fun `manual temporary preview does not authorize random use`() {
        val pool = ActionPool(mapOf(PetAction.LAUGH_FALL to ActionPolicy(PetAction.LAUGH_FALL, 2,
            MaterialStatus.TEMPORARY, false, false, true)),
            mapOf(PetAction.LAUGH_FALL to ActionAssetFacts(true, true, true)))
        assertTrue(pool.canManual(PetAction.LAUGH_FALL))
        assertFalse(pool.automaticActions.contains(PetAction.LAUGH_FALL))
    }

    @Test fun `zero weights and excluded candidates are never selected`() {
        val policy = mapOf(
            PetAction.BLINK to ActionPolicy(PetAction.BLINK, 0, MaterialStatus.FORMAL, true, true, true),
            PetAction.WAVE to ActionPolicy(PetAction.WAVE, 9, MaterialStatus.FORMAL, true, true, true))
        val pool = ActionPool(policy, policy.keys.associateWith { ActionAssetFacts(true, true, false, true) })
        assertEquals(PetAction.WAVE, pool.choose(FixedRandom(0)))
        assertNull(pool.choose(FixedRandom(0)) { it != PetAction.WAVE })
    }

    @Test fun `decode failure revokes an automatically approved action for this session`() {
        val pool = approvedPool(); pool.disable(PetAction.BLINK)
        assertFalse(pool.automaticActions.contains(PetAction.BLINK))
        assertFalse(pool.canManual(PetAction.BLINK))
        assertEquals(PetAction.WAVE, pool.choose(FixedRandom(0)))
    }

    @Test fun `negative and excessive weights are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionPolicy(PetAction.BLINK, -1, MaterialStatus.FORMAL, true, true, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActionPolicy(PetAction.BLINK, 10001, MaterialStatus.FORMAL, true, true, true)
        }
    }
}

internal fun weights() = linkedMapOf(PetAction.BLINK to 40, PetAction.WAVE to 20, PetAction.HAPPY to 18,
    PetAction.WALK to 15, PetAction.LAUGH_FALL to 2, PetAction.WOBBLE to 5)

/** Pure virtual fixtures, not approval of any packaged PNG. */
internal fun approvedPool() = ActionPool(weights().mapValues { (action, weight) ->
    ActionPolicy(action, weight, MaterialStatus.FORMAL, true, true, true)
}, weights().keys.associateWith { ActionAssetFacts(true, true, false, true) })

internal class FixedRandom(private val value: Int) : BehaviorRandom {
    override fun nextInt(bound: Int): Int = value.also { require(it in 0 until bound) }
}
