package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.PetAction
import org.junit.Assert.*
import org.junit.Test

class FormalMaterialTest {
    @Test fun `formal and quality flags alone cannot authorize automatic or visible playback`() {
        val rule = ActionPolicy(PetAction.WAVE, 20, MaterialStatus.FORMAL, true, true, true)
        val pool = ActionPool(mapOf(PetAction.WAVE to rule),
            mapOf(PetAction.WAVE to ActionAssetFacts(true, true, false)))
        assertTrue("Missing image authorization must block the automatic pool", pool.automaticActions.isEmpty())
        assertFalse(pool.canDisplay(PetAction.WAVE))
        assertFalse(pool.canManualFormal(PetAction.WAVE))
    }

    @Test fun `manual only reviewed action is displayable without entering automatic pool`() {
        val rule = ActionPolicy(PetAction.WAVE, 20, MaterialStatus.FORMAL, true, false, true)
        val pool = ActionPool(mapOf(PetAction.WAVE to rule),
            mapOf(PetAction.WAVE to ActionAssetFacts(true, true, false, true)))
        assertTrue(pool.canManualFormal(PetAction.WAVE))
        assertTrue(pool.automaticActions.isEmpty())
    }

    @Test fun `private reference scope requires complete traceable evidence`() {
        val source = MaterialSource("private-personal", "user-reference+imagegen",
            "naiwa/notices/private-reference.txt", "a".repeat(64))
        assertTrue(source.declared)
        assertFalse(source.copy(usageScope = "unverified").declared)
        assertFalse(source.copy(evidencePath = "../reference.txt").declared)
        assertFalse(source.copy(evidenceSha256 = "").declared)
    }
}
