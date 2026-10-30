package com.naiwa.androidpet.animation

data class FacingAnimation(val direction: PetDirection, val clip: AnimationClip)

/** Add an action under any direction without changing the playback state machine. */
class DirectionalAnimationSet(front: Map<PetAction, AnimationClip>,
    directions: Map<PetDirection, Map<PetAction, AnimationClip>>) {
    private val front = front.toMap()
    private val directions = directions.mapValues { it.value.toMap() }
    private val staticWalks = this.directions.mapNotNull { (direction, clips) ->
        clips[PetAction.IDLE]?.let { idle -> direction to AnimationClip(PetAction.WALK,
            idle.frames, idle.fps, true, frameDurationsMillis = idle.frameDurationsMillis) }
    }.toMap()

    init {
        require(this.front.all { it.key == it.value.action })
        require(this.directions.values.all { clips -> clips.all { it.key == it.value.action } })
    }

    fun resolve(action: PetAction, direction: PetDirection, origin: ActionOrigin): FacingAnimation? {
        // A direction can extend only actions already admitted by the existing material gate.
        val original = front[action] ?: return null
        if (direction != PetDirection.FRONT) {
            directions[direction]?.get(action)?.let { return FacingAnimation(direction, it) }
            if (action == PetAction.WALK && origin in setOf(ActionOrigin.MOVEMENT, ActionOrigin.MANUAL_MOVEMENT))
                staticWalks[direction]?.let { return FacingAnimation(direction, it) }
        }
        return FacingAnimation(PetDirection.FRONT, original)
    }
}
