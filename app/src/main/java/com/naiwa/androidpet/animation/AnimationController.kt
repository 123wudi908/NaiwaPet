package com.naiwa.androidpet.animation

enum class ActionGroup { BASIC, FUNNY, EXTENSION }

enum class PetAction(val directory: String, val optional: Boolean = false, val group: ActionGroup = ActionGroup.BASIC) {
    IDLE("idle"), BLINK("blink"), WAVE("wave"), HAPPY("happy"), WALK("walk", optional = true),
    LAUGH_FALL("laugh_fall", true, ActionGroup.FUNNY), WOBBLE("wobble", true, ActionGroup.FUNNY),
    THINK("think", true), LAUGH("laugh", true), BELLY_DRUM("belly_drum", true),
    DANCE("dance", true, ActionGroup.FUNNY), DANCE_SUBWAY("dance_subway", true, ActionGroup.FUNNY),
    FAKE_FALL("fake_fall", true, ActionGroup.FUNNY), ROLL_LAUGH("roll_laugh", true, ActionGroup.FUNNY),
    LOADING_BRAIN("loading_brain", true, ActionGroup.FUNNY), FREEZE("freeze", true, ActionGroup.FUNNY),
    SIT("sit", true, ActionGroup.EXTENSION), SLEEP("sleep", true, ActionGroup.EXTENSION),
    LOOK_AROUND("look_around", true, ActionGroup.EXTENSION), ANGRY("angry", true, ActionGroup.EXTENSION),
    TAP_REACT("tap_react", true, ActionGroup.EXTENSION), SURPRISED("surprised", true, ActionGroup.EXTENSION),
    CRY("cry", true, ActionGroup.EXTENSION), DRINK("drink", true, ActionGroup.EXTENSION)
}

enum class ActionOrigin { DEFAULT, AUTOMATIC, MOVEMENT, TAP, GAME, MANUAL, MANUAL_MOVEMENT }

class AnimationClip(val action: PetAction, frames: List<String>, val fps: Int, val loop: Boolean,
    val lastFrameHoldMillis: Long = 0, frameDurationsMillis: List<Long> = emptyList(),
    val interruptible: Boolean = true) {
    val frames: List<String> = frames.toList()
    val frameDurationsMillis = frameDurationsMillis.toList()
    init {
        require(this.frames.isNotEmpty() && this.frames.size <= 120) { "${action.directory}: expected 1–120 PNG frames" }
        require(fps in 1..30) { "${action.directory}: fps must be 1–30" }
        require(this.frameDurationsMillis.isEmpty() || (this.frameDurationsMillis.size == this.frames.size &&
            this.frameDurationsMillis.all { it in 16..10_000L })) { "${action.directory}: invalid per-frame timing" }
        require(lastFrameHoldMillis in 0L..10_000L && (lastFrameHoldMillis == 0L || !loop)) {
            "${action.directory}: lastFrameHoldMillis must be 0–10000 and only applies to non-looping clips"
        }
    }
    val frameDurationMillis: Long get() = 1000L / fps
    fun durationAt(index: Int): Long = frameDurationsMillis.getOrNull(index) ?: frameDurationMillis
}

fun interface Cancellable { fun cancel() }
interface FrameScheduler {
    val nowMillis: Long
    fun post(delayMillis: Long, action: () -> Unit): Cancellable
}
interface FrameSink {
    /** Complete on the same thread as the controller. A newer request invalidates older deliveries. */
    fun present(path: String, completion: (Result<Unit>) -> Unit)
    /** Cancel asynchronous deliveries without erasing the last successfully displayed frame. */
    fun cancelPending() {}
    fun clear()
    fun close()
}

/** Main-thread state machine; no Android, Bitmap, Service or asset IO dependency. */
class AnimationController(
    clips: Map<PetAction, AnimationClip>,
    private val scheduler: FrameScheduler,
    private val sink: FrameSink,
    private val diagnostic: (String) -> Unit,
) {
    private val clips = clips.toMap()
    private var clip: AnimationClip? = null
    private var index = 0
    private var generation = 0L
    private var scheduled: Cancellable? = null
    private var closed = false
    private val failedIdleFrames = mutableSetOf<String>()
    private var motionEnabled = true
    var isPaused = true
        private set
    val currentAction: PetAction? get() = clip?.action
    var currentOrigin = ActionOrigin.DEFAULT
        private set
    var actionListener: ((PetAction) -> Unit)? = null
    var failureListener: ((PetAction) -> Unit)? = null

    /** All producers use this one gate and the existing frame state machine. */
    fun request(action: PetAction, origin: ActionOrigin): Boolean {
        if (closed) return false
        if (origin == ActionOrigin.AUTOMATIC || origin == ActionOrigin.MOVEMENT) {
            if (isPaused || currentAction != PetAction.IDLE) return false
        }
        if (origin == ActionOrigin.GAME && (isPaused || (currentAction != PetAction.IDLE &&
                currentOrigin in setOf(ActionOrigin.MANUAL, ActionOrigin.MANUAL_MOVEMENT)))) return false
        if (origin == ActionOrigin.TAP && (isPaused || clip?.interruptible == false || currentAction == PetAction.LAUGH_FALL ||
                (currentAction != PetAction.IDLE && currentOrigin in setOf(ActionOrigin.MANUAL,ActionOrigin.MANUAL_MOVEMENT,ActionOrigin.GAME)))) return false
        return begin(action, origin)
    }

    fun finishMovement() {
        if (!closed && currentAction == PetAction.WALK &&
            currentOrigin in setOf(ActionOrigin.MOVEMENT, ActionOrigin.MANUAL_MOVEMENT))
            begin(PetAction.IDLE, ActionOrigin.DEFAULT)
    }

    init { require(this.clips.all { it.key == it.value.action }) }

    fun start() { if (!begin(PetAction.IDLE, ActionOrigin.DEFAULT)) pause() }

    fun play(action: PetAction): Boolean = request(action, ActionOrigin.MANUAL)

    private fun begin(action: PetAction, origin: ActionOrigin): Boolean {
        if (closed) return false
        val next = clips[action]
        if (next == null) {
            diagnostic("缺少可用 PNG 动作：assets/naiwa/${action.directory}/")
            return false
        }
        invalidate()
        clip = next; index = 0; isPaused = false; currentOrigin = origin
        actionListener?.invoke(action)
        render()
        return true
    }

    private fun render() {
        val playing = clip ?: return
        if (closed || isPaused) return
        if (playing.action == PetAction.IDLE && playing.frames[index] in failedIdleFrames) {
            val healthy = playing.frames.indices.firstOrNull { playing.frames[it] !in failedIdleFrames }
            if (healthy == null) { pause(); return }
            index = healthy
        }
        val epoch = generation
        val path = playing.frames[index]
        val started = scheduler.nowMillis
        sink.present(path) { result ->
            if (closed || isPaused || epoch != generation) return@present
            if (result.isFailure) {
                diagnostic("PNG 播放失败：assets/$path；${result.exceptionOrNull()?.message ?: "无法解码"}")
                if (playing.action == PetAction.IDLE) {
                    failedIdleFrames.add(path)
                    val healthy = playing.frames.indices.firstOrNull { playing.frames[it] !in failedIdleFrames }
                    if (healthy != null) { index = healthy; render() }
                    else { pause(); failureListener?.invoke(playing.action) }
                } else {
                    if (!begin(PetAction.IDLE, ActionOrigin.DEFAULT)) pause()
                    failureListener?.invoke(playing.action)
                }
                return@present
            }
            if (!motionEnabled) {
                if (playing.action != PetAction.IDLE && playing.action != PetAction.WALK) {
                    val duration = playing.frames.indices.sumOf(playing::durationAt) +
                        (playing.lastFrameHoldMillis-playing.durationAt(playing.frames.lastIndex)).coerceAtLeast(0)
                    scheduled = scheduler.post(duration) {
                        if (!closed && !isPaused && epoch == generation) begin(PetAction.IDLE,ActionOrigin.DEFAULT)
                    }
                }
                return@present
            }
            val holdingLastFrame = !playing.loop && index == playing.frames.lastIndex && playing.lastFrameHoldMillis > 0
            // Count a final-pose hold from successful presentation, including on a slow decoder.
            val remaining = if (holdingLastFrame) maxOf(playing.durationAt(index), playing.lastFrameHoldMillis)
                else (playing.durationAt(index) - (scheduler.nowMillis - started)).coerceAtLeast(0)
            scheduled = scheduler.post(remaining) {
                if (closed || isPaused || epoch != generation) return@post
                when {
                    index + 1 < playing.frames.size -> { index++; render() }
                    playing.loop -> { index = 0; render() }
                    playing.action == PetAction.IDLE -> { scheduled = null; isPaused = true }
                    else -> if (!begin(PetAction.IDLE, ActionOrigin.DEFAULT)) pause()
                }
            }
        }
    }

    fun pause() {
        if (closed) return
        invalidate(); isPaused = true
        sink.cancelPending()
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (closed || motionEnabled == enabled) return
        motionEnabled = enabled
        if (!isPaused) { invalidate(); sink.cancelPending(); render() }
    }

    fun resume() {
        if (closed || !isPaused) return
        if (clip == null) { start(); return }
        invalidate(); isPaused = false
        render()
    }

    fun close() {
        if (closed) return
        closed = true; isPaused = true
        actionListener = null
        failureListener = null
        invalidate()
        sink.close()
    }

    private fun invalidate() {
        generation++
        scheduled?.cancel(); scheduled = null
    }
}
