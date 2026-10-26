package com.naiwa.androidpet.ui

import com.naiwa.androidpet.R
import com.naiwa.androidpet.animation.PetAction

fun PetAction.labelResource(): Int = when (this) {
    PetAction.IDLE -> R.string.action_idle
    PetAction.BLINK -> R.string.action_blink
    PetAction.WAVE -> R.string.action_wave
    PetAction.HAPPY -> R.string.action_happy
    PetAction.WALK -> R.string.action_walk
    PetAction.THINK -> R.string.action_think
    PetAction.LAUGH -> R.string.action_laugh
    PetAction.BELLY_DRUM -> R.string.action_belly_drum
    PetAction.DANCE -> R.string.action_dance
    PetAction.DANCE_SUBWAY -> R.string.action_dance_subway
    PetAction.FAKE_FALL -> R.string.action_fake_fall
    PetAction.ROLL_LAUGH -> R.string.action_roll_laugh
    PetAction.LAUGH_FALL -> R.string.action_laugh_fall
    PetAction.LOADING_BRAIN -> R.string.action_loading_brain
    PetAction.FREEZE -> R.string.action_freeze
    PetAction.WOBBLE -> R.string.action_wobble
    PetAction.SIT -> R.string.action_sit
    PetAction.SLEEP -> R.string.action_sleep
    PetAction.LOOK_AROUND -> R.string.action_look_around
    PetAction.ANGRY -> R.string.action_angry
    PetAction.TAP_REACT -> R.string.action_tap_react
    PetAction.SURPRISED -> R.string.action_surprised
    PetAction.CRY -> R.string.action_cry
    PetAction.DRINK -> R.string.action_drink
}
