package com.naiwa.androidpet.companion

import com.naiwa.androidpet.animation.PetAction

object MoodBehavior {
    fun preferred(state: PetState, preferMood: Boolean): Set<PetAction> = when {
        state.energy < 25 -> setOf(PetAction.BLINK,PetAction.THINK,PetAction.WOBBLE,PetAction.FREEZE,PetAction.LOADING_BRAIN)
        preferMood && state.affinity >= 30 -> setOf(PetAction.WAVE,PetAction.HAPPY,PetAction.LAUGH,PetAction.DANCE)
        preferMood && state.happiness >= 85 -> setOf(PetAction.HAPPY,PetAction.DANCE,PetAction.LAUGH,PetAction.WAVE)
        preferMood && state.happiness < 45 -> setOf(PetAction.BLINK,PetAction.WAVE,PetAction.HAPPY)
        else -> PetAction.entries.toSet()
    }
}
