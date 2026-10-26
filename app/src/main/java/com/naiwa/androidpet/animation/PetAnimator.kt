package com.naiwa.androidpet.animation

import android.content.Context
import com.naiwa.androidpet.R
import com.naiwa.androidpet.core.PetSize
import com.naiwa.androidpet.overlay.PetFrameView
import com.naiwa.androidpet.behavior.*
import com.naiwa.androidpet.runtime.PetDiagnostics
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.data.PetStateRepository
import com.naiwa.androidpet.companion.*
import com.naiwa.androidpet.ui.FeedbackPlayer

/** Small adapter between asset metadata, Android decoding, and the pure playback controller. */
class PetAnimator(context: Context, view: PetFrameView, host: HorizontalMoveHost,
    onDiagnostic: (String) -> Unit, private val onFeedback: (String) -> Unit = {},
    private val rememberInteractions: Boolean = true) {
    private val preferences = PetPreferences(context)
    private val memory = PetStateRepository.get(context)
    private val combo = ComboTracker()
    private val sound = FeedbackPlayer(context)
    private val materials = PetMaterials.load(context)
    private val catalog = materials.catalog
    private val behaviorConfig = materials.behavior
    private val pool = materials.pool
    private val approvedClips = catalog.clips.filterKeys(pool::canDisplay)
    private val sink = PngFrameSink(context.assets, catalog.layout, view, catalog.frameLayouts)
    private val scheduler = AndroidFrameScheduler()
    private val controller = AnimationController(approvedClips, scheduler, sink, onDiagnostic)
    private val interaction = PetInteractionController(controller,scheduler,
        BehaviorRandom { kotlin.random.Random.Default.nextInt(it) },pool,behaviorConfig.settings,host,
        { behaviorConfig.settings.moveSpeedDpPerSecond*context.resources.displayMetrics.density },memory::read)
    private var stateListener: ((BehaviorSnapshot) -> Unit)? = null
    private var started = false
    private var visible = false
    private var lastPixels = 0
    private var previousAction: PetAction? = null
    private var previousMovement: Boolean? = null
    val canStart: Boolean get() = PetAction.IDLE in approvedClips
    val materialInfo: String = context.getString(if (canStart) R.string.personal_material else R.string.materials_unavailable)
    val initialDiagnostic: String? = (catalog.diagnostics+behaviorConfig.diagnostics+
        if (canStart) emptyList() else listOf(context.getString(R.string.idle_required)))
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
    val automaticByDefault get() = behaviorConfig.settings.automaticByDefault
    val movementByDefault get() = behaviorConfig.settings.movementByDefault
    val snapshot get() = interaction.snapshot

    init {
        controller.actionListener = {
            previousAction?.let { old -> PetDiagnostics.event("animation.end", "action=${old.directory} next=${it.directory}") }
            previousAction = it
            PetDiagnostics.event("animation.start", "action=${it.directory} origin=${controller.currentOrigin}")
            memory.discover(it.directory)
            if (it != PetAction.WALK) view.mirrored = false
            interaction.onActionChanged()
        }
        controller.failureListener = {
            PetDiagnostics.event("animation.failed", "action=${it.directory} paused=${controller.isPaused}")
            interaction.onActionFailed(it)
        }
        interaction.stateListener = { state ->
            if(previousMovement!=state.moving){
                previousMovement=state.moving
                PetDiagnostics.event(if(state.moving)"movement.start" else "movement.pause_or_end")
            }
            stateListener?.invoke(state)
        }
    }

    fun hasAction(action: PetAction): Boolean = action in approvedClips
    val currentAction: PetAction? get() = controller.currentAction
    fun canManual(action: PetAction) = pool.canManualFormal(action)
    fun setStateListener(listener: ((BehaviorSnapshot) -> Unit)?) { stateListener = listener }
    fun setAutomaticEnabled(enabled: Boolean) {
        if(snapshot.automaticEnabled!=enabled)PetDiagnostics.event("behavior.setting","enabled=$enabled")
        interaction.setAutomaticEnabled(enabled)
    }
    fun setMovementEnabled(enabled: Boolean) {
        if(snapshot.movementEnabled!=enabled)PetDiagnostics.event("movement.setting","enabled=$enabled")
        interaction.setMovementEnabled(enabled)
    }
    fun onTouchDown() = interaction.onTouchDown()
    fun onTouchReleased() = interaction.onTouchReleased()
    fun onTap(): Boolean {
        val feedback = combo.tap(scheduler.nowMillis)
        if (!feedback.accepted) return true
        if (rememberInteractions) memory.interact(if (feedback.count == 6) InteractionType.COMBO else InteractionType.TAP)
        if (feedback.animate) {
            sound.play()
            if (preferences.feedbackEnabled) onFeedback(when (feedback.level) {
                2 -> "好啦好啦，我要笑倒了！"
                1 -> "再点下去，肚皮要笑酸了。"
                else -> listOf("我在呢。", "今天也一起待一会儿。", "摸到了，是软软的肚皮。")[(feedback.count-1)%3]
            })
        }
        return if (feedback.animate) interaction.onTap(feedback.level) else true
    }
    fun onSwipe(): Boolean {
        combo.reset()
        if (rememberInteractions) memory.interact(InteractionType.SWIPE)
        if (preferences.feedbackEnabled) onFeedback("晃一下，站稳啦。")
        sound.play()
        return interaction.onSwipe()
    }
    fun onDragged() { combo.reset(); if (rememberInteractions) memory.interact(InteractionType.DRAG) }
    fun gameFeedback(action: PetAction) = interaction.gameFeedback(action)
    fun refreshSettings() { controller.setMotionEnabled(preferences.animationsEnabled) }
    fun setPanelOpen(open: Boolean) {
        PetDiagnostics.event("tasks.panel","open=$open")
        interaction.setPanelOpen(open)
    }
    fun onGeometryChanged() = interaction.onGeometryChanged()

    fun preferredSize(width: Int): PetSize = catalog.layout.scaledViewport(width,catalog.referenceHeight)

    fun resize(size: PetSize) {
        val pixels = maxOf(size.width, size.height).coerceAtMost(1024)
        if (pixels == lastPixels) return
        lastPixels = pixels
        controller.pause(); sink.resize(pixels)
        if (visible) controller.resume()
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        PetDiagnostics.event(if (visible) "tasks.resume" else "tasks.pause", "action=${controller.currentAction}")
        this.visible = visible
        refreshSettings()
        if (!visible) { interaction.setVisible(false); controller.pause() }
        else {
            if (!started) { started = true; controller.start() } else controller.resume()
            interaction.setVisible(true)
        }
    }

    fun play(action: PetAction): Boolean = visible && canManual(action) && interaction.manualAction(action)
    fun trimMemory() = sink.trimMemory()
    fun close() { PetDiagnostics.event("animation.close"); stateListener = null; combo.reset(); sound.close(); interaction.close(); controller.close() }
}
