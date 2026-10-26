package com.naiwa.androidpet.companion

import com.naiwa.androidpet.animation.PetAction
import org.junit.Assert.*
import org.junit.Test

class MoodBehaviorTest {
    @Test fun `low energy selects quiet actions and friendship changes social preference`() {
        val quiet = MoodBehavior.preferred(PetState(energy=10), true)
        assertTrue(PetAction.BLINK in quiet)
        assertFalse(PetAction.DANCE in quiet)
        val social = MoodBehavior.preferred(PetState(affinity=40), true)
        assertTrue(PetAction.WAVE in social)
        assertFalse(PetAction.FREEZE in social)
    }
}
