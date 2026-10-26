package com.naiwa.androidpet.companion

import org.junit.Assert.*
import org.junit.Test

class PetStateTest {
    @Test fun `offline recovery adds energy without punishing mood or friendship`() {
        val state = PetState(happiness=83, energy=14, affinity=27, lastRecoveryTime=1000)
        val restored = state.recover(86_401_000)
        assertEquals(100, restored.energy)
        assertEquals(83, restored.happiness)
        assertEquals(27, restored.affinity)
        assertEquals(0, restored.interactionCount)
    }
    @Test fun `rapid interactions do not inflate rewards but memory remains bounded`() {
        var state = PetState()
        repeat(100) { state = state.interact(InteractionType.TAP, 10_000L + it*100L) }
        assertEquals(100, state.interactionCount)
        assertTrue(state.happiness in 72..84)
        assertTrue(state.energy in 70..80)
        assertTrue(state.recentInteractions.size <= 16)
        assertTrue("first_touch" in state.achievements)
        assertEquals(1, state.achievements.count { it == "first_touch" })
    }
    @Test fun `same game settlement cannot grant rewards or change best twice`() {
        val first = PetState().settleGame("one", "drum", 120, 1000)
        val repeated = first.settleGame("one", "drum", 999, 2000)
        assertEquals(first, repeated)
        assertEquals(120, first.bestScores["drum"])
    }
    @Test fun `three different games unlock one achievement and preserve each best`() {
        var state = PetState()
        for ((id,kind) in listOf("a" to "drum", "b" to "combo", "c" to "bubbles"))
            state = state.settleGame(id, kind, 40, 1000)
        assertTrue("all_games" in state.achievements)
        assertEquals(3, state.bestScores.size)
        state = state.settleGame("d", "drum", 1, 2000)
        assertEquals(40, state.bestScores["drum"])
    }
    @Test fun `state codec round trips real memory and rejects corrupt numeric values`() {
        val state = PetState().interact(InteractionType.SWIPE, 1200).discover("wave")
            .settleGame("session-1", "bubbles", 70, 2400)
        assertEquals(state, PetStateCodec.decode(PetStateCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) { PetStateCodec.decode("version=1\nhappiness=no") }
    }
    @Test fun `clock rollback cannot lower last interaction or create negative energy`() {
        val state = PetState(happiness=99, energy=0, affinity=100, lastInteractionTime=9000)
            .interact(InteractionType.COMBO,100)
        assertEquals(9000, state.lastInteractionTime)
        assertTrue(state.energy >= 0)
        assertTrue(state.happiness <= 100)
    }
}
