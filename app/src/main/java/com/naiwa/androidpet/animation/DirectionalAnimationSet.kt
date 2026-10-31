package com.naiwa.androidpet.animation

data class FacingAnimation(val direction: PetDirection, val clip: AnimationClip, val mirrored: Boolean = false,
    val directionalArt: Boolean = direction != PetDirection.FRONT)

/** Add an action under any direction without changing the playback state machine. */
class DirectionalAnimationSet(front: Map<PetAction, AnimationClip>,
    directions: Map<PetDirection, Map<PetAction, AnimationClip>>) {
    private val front = front.toMap()
    private val directions = directions.mapValues { it.value.toMap() }

    init {
        require(this.front.all { it.key == it.value.action })
        require(this.directions.values.all { clips -> clips.all { it.key == it.value.action } })
    }

    fun resolve(action: PetAction, direction: PetDirection, origin: ActionOrigin): FacingAnimation? {
        // A direction can extend only actions already admitted by the existing material gate.
        val original = front[action] ?: return null
        if (action == PetAction.WALK) {
            // The admitted six PNGs already hold the belly and alternate the legs, facing right.
            // Mirror the complete frame for leftward travel, including head, hands and feet.
            val left = direction in setOf(PetDirection.FRONT_LEFT, PetDirection.LEFT, PetDirection.BACK_LEFT)
            return FacingAnimation(if (left) PetDirection.LEFT else PetDirection.RIGHT, original,
                mirrored = left, directionalArt = false)
        }
        if (direction != PetDirection.FRONT) {
            directions[direction]?.get(action)?.let { return FacingAnimation(direction, it) }
        }
        return FacingAnimation(PetDirection.FRONT, original)
    }
}
