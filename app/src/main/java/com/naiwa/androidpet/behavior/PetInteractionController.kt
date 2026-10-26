package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.companion.PetState

data class BehaviorSnapshot(val action: PetAction?, val origin: ActionOrigin, val automaticEnabled: Boolean,
    val movementEnabled: Boolean, val automaticActions: Set<PetAction>, val moving: Boolean,
    val blockedReasons: Map<PetAction,String>)

class PetInteractionController(private val animation: AnimationController, private val scheduler: FrameScheduler,
    private val random: BehaviorRandom, val pool: ActionPool, private val settings: BehaviorSettings,
    host: HorizontalMoveHost, speedPixelsPerSecond: () -> Double, petState: () -> PetState = { PetState() }) {
    private var visible = false
    private var closed = false
    private var touching = false
    private var panelOpen = false
    private var releaseHold = false
    private var manualHold = false
    private var issuingManual = false
    private var manualWalkRequested = false
    private val touchEligibleAt = mutableMapOf<PetAction,Long>()
    private val release = BehaviorTimer(scheduler)
    private var automaticEnabled = settings.automaticByDefault
    private var movementEnabled = settings.movementByDefault
    var stateListener: ((BehaviorSnapshot) -> Unit)? = null
    private val movement = AutoMoveController(scheduler,random,host,settings,
        { PetAction.WALK in pool.automaticActions },
        { animation.request(PetAction.WALK, if (manualWalkRequested) ActionOrigin.MANUAL_MOVEMENT else ActionOrigin.MOVEMENT) },
        animation::finishMovement,speedPixelsPerSecond,::emit)
    private val automatic = AutoBehaviorController(scheduler,random,pool,settings,
        { animation.request(it,ActionOrigin.AUTOMATIC) },movement::queueMovement,{movementEnabled},petState)

    init { automatic.setEnabled(automaticEnabled); movement.setEnabled(movementEnabled) }
    val snapshot: BehaviorSnapshot get() = BehaviorSnapshot(animation.currentAction,animation.currentOrigin,
        automaticEnabled,movementEnabled,pool.automaticActions,movement.isMoving,
        PetAction.entries.mapNotNull { action -> pool.blockedReason(action)?.let { action to it } }.toMap())

    fun setVisible(visible: Boolean) {
        if (closed) return
        this.visible = visible
        if (!visible) { touching = false; releaseHold = false; release.cancel() }
        syncSuspension(); emit()
    }
    fun setAutomaticEnabled(enabled: Boolean) {
        if (closed) return
        automaticEnabled = enabled
        if (!enabled) movement.clearQueued()
        automatic.setEnabled(enabled); syncSuspension(); emit()
    }
    fun setMovementEnabled(enabled: Boolean) {
        if (closed) return
        movementEnabled = enabled; movement.setEnabled(enabled)
        syncSuspension(); emit()
    }
    fun onActionChanged() {
        if (closed) return
        if (animation.currentOrigin != ActionOrigin.DEFAULT) animation.currentAction?.let(automatic::onActionStarted)
        if (animation.currentAction == PetAction.IDLE) manualWalkRequested = false
        if (animation.currentAction == PetAction.IDLE && !issuingManual) manualHold = false
        syncSuspension(); emit()
    }
    fun onActionFailed(action: PetAction) {
        if (closed) return
        pool.disable(action); syncSuspension(); emit()
    }
    fun onTouchDown() {
        if (closed) return
        touching = true; release.cancel(); releaseHold = false
        syncSuspension(); emit()
    }
    fun onTouchReleased() {
        if (closed) return
        touching = false; delayRelease()
    }
    fun setPanelOpen(open: Boolean) {
        if (closed || panelOpen == open) return
        panelOpen = open
        if (open) { syncSuspension(); emit() } else delayRelease()
    }
    fun onGeometryChanged() { if (!closed) delayRelease() }

    private fun delayRelease() {
        release.cancel(); releaseHold = visible
        syncSuspension(); emit()
        if (visible) release.post(settings.releaseDelayMillis) {
            if (closed) return@post
            releaseHold = false; syncSuspension(); emit()
        }
    }

    fun manualAction(action: PetAction): Boolean {
        if (action != PetAction.WALK) return issue(action,ActionOrigin.MANUAL)
        if (closed || !visible || touching || panelOpen || !pool.canManualFormal(action)) return false
        release.cancel(); releaseHold = false; manualHold = false
        animation.request(PetAction.IDLE, ActionOrigin.MANUAL)
        syncSuspension()
        manualWalkRequested = true
        val accepted = movement.moveOnce()
        if (!accepted) manualWalkRequested = false
        syncSuspension(); emit()
        return accepted
    }
    fun onTap(level: Int = 0): Boolean {
        if (closed || !visible || animation.currentAction == PetAction.LAUGH_FALL ||
            (animation.currentAction != PetAction.IDLE && animation.currentOrigin == ActionOrigin.MANUAL)) return false
        val small = when (level) {
            2 -> setOf(PetAction.LAUGH_FALL,PetAction.FAKE_FALL,PetAction.DANCE,PetAction.LAUGH)
            1 -> setOf(PetAction.HAPPY,PetAction.LAUGH,PetAction.WOBBLE)
            else -> setOf(PetAction.BLINK, PetAction.WAVE, PetAction.HAPPY, PetAction.WOBBLE, PetAction.TAP_REACT)
        }
        val action = pool.choose(random) { it in small && pool.canManualFormal(it) &&
            scheduler.nowMillis >= (touchEligibleAt[it] ?: 0) } ?: return false
        return issue(action,ActionOrigin.TAP)
    }
    fun onSwipe(): Boolean = issue(PetAction.WOBBLE,ActionOrigin.TAP)
    fun gameFeedback(action: PetAction): Boolean = issue(action,ActionOrigin.GAME)
    private fun issue(action: PetAction,origin: ActionOrigin): Boolean {
        if (closed || !visible || !pool.canManual(action)) return false
        if (origin == ActionOrigin.TAP && scheduler.nowMillis < (touchEligibleAt[action] ?: 0)) return false
        issuingManual = true; manualHold = true
        syncSuspension()
        val accepted = try { animation.request(action,origin) } finally { issuingManual = false }
        if (accepted && origin == ActionOrigin.TAP)
            touchEligibleAt[action] = scheduler.nowMillis + (pool.policies[action]?.cooldownMillis ?: 0)
        manualHold = accepted && animation.currentAction != PetAction.IDLE
        syncSuspension(); emit()
        return accepted
    }

    private fun syncSuspension() {
        val blocked = closed || !visible || touching || panelOpen || releaseHold || manualHold || animation.isPaused
        automatic.setSuspended(blocked || animation.currentAction != PetAction.IDLE)
        // Ordinary short actions must not reset the independent 30–90s deadline.
        // At that deadline the animation gate rejects a walk if another action is busy.
        val manualActionActive = animation.currentAction != PetAction.IDLE &&
            (animation.currentOrigin in setOf(ActionOrigin.MANUAL,ActionOrigin.TAP,ActionOrigin.GAME))
        movement.setSuspended(blocked || manualActionActive)
    }
    private fun emit() { if (!closed) stateListener?.invoke(snapshot) }
    fun close() {
        if (closed) return
        closed = true; visible = false; release.cancel(); automatic.close(); movement.close(); stateListener = null
    }
}
