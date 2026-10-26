package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.core.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

interface HorizontalMoveHost {
    val position: Position
    val size: PetSize
    val area: ScreenArea
    fun moveTo(position: Position)
    fun savePosition()
}

class AutoMoveController(private val scheduler: FrameScheduler, private val random: BehaviorRandom,
    private val host: HorizontalMoveHost, private val settings: BehaviorSettings,
    private val canUseWalk: () -> Boolean, private val beginWalk: () -> Boolean,
    private val endWalk: () -> Unit, private val speedPixelsPerSecond: () -> Double,
    private val onMovingChanged: () -> Unit = {}) {
    private val decision = BehaviorTimer(scheduler)
    private val tick = BehaviorTimer(scheduler)
    private var enabled = false
    private var suspended = true
    private var closed = false
    private var queued = false
    private var manualOnce = false
    private var origin = Position(0,0)
    private var targetX = 0
    private var started = 0L
    private var duration = 1L
    var isMoving = false
        private set

    private fun runnable() = !closed && (enabled || manualOnce) && !suspended && canUseWalk() &&
        host.area.width > WindowGeometry.fit(host.size,host.area).width

    fun setEnabled(enabled: Boolean) {
        if (closed) return
        if (!enabled) manualOnce = false
        this.enabled = enabled; refresh()
    }
    fun setSuspended(suspended: Boolean) {
        if (closed) return
        this.suspended = suspended; refresh()
    }
    private fun refresh() {
        if (!runnable()) { decision.cancel(); queued = false; stopMoving(); return }
        if (isMoving || decision.pending) return
        decision.post(settings.moveDelay(random)) {
            if (!runnable()) return@post
            val shouldMove = queued || random.nextInt(2) == 0
            queued = false
            if (shouldMove) startMoving()
            refresh()
        }
    }
    fun queueMovement(): Boolean {
        if (!runnable()) return false
        queued = true; refresh(); return true
    }
    fun clearQueued() { queued = false }

    /** An explicit user command does not change the persisted automatic-movement switch. */
    fun moveOnce(): Boolean {
        if (closed || suspended || !canUseWalk()) return false
        manualOnce = true
        decision.cancel(); queued = false
        startMoving()
        if (!isMoving) manualOnce = false
        return isMoving
    }

    private fun startMoving() {
        val area = host.area
        val size = WindowGeometry.fit(host.size,area)
        origin = WindowGeometry.clamp(host.position,size,area)
        val maxX = area.right-size.width
        if (maxX <= area.left) return
        targetX = area.left+random.nextInt(maxX-area.left+1)
        val minimumTravel = minOf(maxOf(origin.x-area.left, maxX-origin.x), maxOf(1, size.width/2))
        if (abs(targetX-origin.x) < minimumTravel)
            targetX = if (maxX-origin.x >= origin.x-area.left) maxX else area.left
        val speed = speedPixelsPerSecond()
        if (!speed.isFinite() || speed <= 0 || targetX == origin.x) return
        duration = ceil(abs(targetX.toDouble()-origin.x)*1000/speed).toLong().coerceAtLeast(16)
        started = scheduler.nowMillis
        isMoving = true
        if (!beginWalk()) { isMoving = false; return }
        decision.cancel()
        onMovingChanged()
        if (isMoving && runnable()) scheduleTick() else stopMoving()
    }

    private fun scheduleTick() {
        if (!isMoving || !runnable()) return
        val remaining = (duration-(scheduler.nowMillis-started)).coerceAtLeast(1)
        tick.post(minOf(16,remaining)) {
            if (!isMoving || !runnable()) { stopMoving(); refresh(); return@post }
            val elapsed = scheduler.nowMillis-started
            val progress = (elapsed.toDouble()/duration).coerceIn(0.0,1.0)
            val next = Position((origin.x+(targetX-origin.x)*progress).roundToInt(),origin.y)
            val safe = WindowGeometry.clamp(next,host.size,host.area)
            if (safe != host.position) host.moveTo(safe)
            if (closed || !isMoving) return@post // A window callback may synchronously close its owner.
            if (elapsed >= duration) { stopMoving(); refresh() } else scheduleTick()
        }
    }

    private fun stopMoving() {
        tick.cancel()
        if (!isMoving) return
        isMoving = false
        manualOnce = false
        endWalk() // The animation gate only ends the walk it still owns.
        host.savePosition()
        onMovingChanged()
    }
    fun close() {
        if (closed) return
        closed = true; queued = false; decision.cancel(); stopMoving()
    }
}
