package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.companion.*

class AutoBehaviorController(private val scheduler: FrameScheduler, private val random: BehaviorRandom,
    private val pool: ActionPool, private val settings: BehaviorSettings,
    private val play: (PetAction) -> Boolean, private val queueWalk: () -> Boolean,
    private val walkEnabled: () -> Boolean, private val petState: () -> PetState = { PetState() }) {
    private val decision = BehaviorTimer(scheduler)
    private var enabled = false
    private var suspended = true
    private var closed = false
    private val eligibleAt = mutableMapOf<PetAction, Long>()
    private fun allowedMode(action: PetAction) = action != PetAction.WALK || walkEnabled()
    private fun allowed(action: PetAction) = allowedMode(action) && scheduler.nowMillis >= (eligibleAt[action] ?: 0)
    private fun runnable() = !closed && enabled && !suspended && pool.automaticActions.any(::allowedMode)

    fun onActionStarted(action: PetAction) {
        if (!closed) eligibleAt[action] = scheduler.nowMillis + (pool.policies[action]?.cooldownMillis ?: 0)
    }

    fun setEnabled(enabled: Boolean) { if (!closed) { this.enabled = enabled; refresh() } }
    fun setSuspended(suspended: Boolean) { if (!closed) { this.suspended = suspended; refresh() } }
    fun refresh() {
        if (!runnable()) { decision.cancel(); return }
        if (decision.pending) return
        decision.post(settings.actionDelay(random)) {
            if (!runnable()) return@post
            val preference = MoodBehavior.preferred(petState(),random.nextInt(3) == 0)
            pool.choose(random) { allowed(it) && it in preference }?.let { action ->
                val accepted = if (action == PetAction.WALK) queueWalk() else play(action)
                if (accepted) onActionStarted(action)
            }
            refresh()
        }
    }
    fun close() { closed = true; decision.cancel() }
}
