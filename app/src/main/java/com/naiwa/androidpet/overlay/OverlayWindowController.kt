package com.naiwa.androidpet.overlay

import android.annotation.SuppressLint

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.widget.Switch
import android.widget.TextView
import com.naiwa.androidpet.MainActivity
import com.naiwa.androidpet.ui.NativeStyle
import com.naiwa.androidpet.ui.labelResource
import com.naiwa.androidpet.animation.ActionGroup
import com.naiwa.androidpet.R
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.animation.PetAction
import com.naiwa.androidpet.animation.PetAnimator
import com.naiwa.androidpet.animation.ActionOrigin
import com.naiwa.androidpet.behavior.BehaviorSnapshot
import com.naiwa.androidpet.behavior.HorizontalMoveHost
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.runtime.PetDiagnostics

/** Owns a small interactive pet window and an optional, disjoint control panel. */
class OverlayWindowController(
    context: Context,
    private val currentPosition: () -> Position,
    private val onMove: (Position) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onClose: () -> Unit,
    private val onResize: (Int) -> Unit,
    private val onBoundsChanged: (ScreenArea) -> Unit,
    private val onDiagnostic: (String) -> Unit,
    private val onFailure: (RuntimeException) -> Unit,
) : OverlaySurface {
    private val display = checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)) {
        "无法获取默认显示屏，不能创建悬浮窗"
    }
    private val displayContext = context.createDisplayContext(display)
    private val windowContext = if (Build.VERSION.SDK_INT >= 30)
        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
    private val viewContext = ContextThemeWrapper(windowContext, R.style.AppTheme)
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val bounds = ScreenBoundsReader(manager)
    private val drag = DragTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var root: FrameLayout? = null
    private var pet: PetFrameView? = PetFrameView(viewContext)
    private var animator: PetAnimator? = null
    private val behaviorPreferences = PetPreferences(context)
    private var params: WindowManager.LayoutParams? = null
    private var petSize = PetSize(1, 1)
    private var position = Position(0, 0)
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAdded = false
    private var panelClose: Button? = null
    private var automaticSwitch: Switch? = null
    private var movementSwitch: Switch? = null
    private var currentActionLabel: TextView? = null
    private var automaticHint: TextView? = null
    private var movementHint: TextView? = null
    private var updatingControls = false
    private val actionButtons = mutableMapOf<PetAction, Button>()
    private var gestureCancelled = false
    private var lastArea: ScreenArea? = null
    private var visibilityAllowed = false
    private var systemWindowVisible = true
    private var windowAdded = false
    private var layoutReady = false
    private var boundsRefreshPosted = false
    private var longPress: Runnable? = null
    private var longPressed = false
    private val recovery = RecoveryBudget(2)
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryPending = false
    private var lastVisibility = ""
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureTime = 0L
    private var inApp = false
    private var recovering = false
    private var recoveryBlocked = false
    private val speech = SpeechBubbleWindow(viewContext,manager)
    val materialInfo: String? get() = animator?.materialInfo
    val canStart: Boolean get() = animator?.canStart == true
    val initialDiagnostic: String? get() = animator?.initialDiagnostic

    init {
        val host = object : HorizontalMoveHost {
            override val position: Position get() = currentPosition()
            override val size: PetSize get() = params?.let { PetSize(it.width,it.height) } ?: petSize
            override val area: ScreenArea get() = screenArea()
            override fun moveTo(position: Position) { safely {
                val before = currentPosition()
                onMove(position)
                val after = currentPosition()
                animator?.onMovement(after.x - before.x, after.y - before.y)
            } }
            override fun savePosition() { safely(onDragEnd) }
        }
        animator = PetAnimator(viewContext,checkNotNull(pet),host,onDiagnostic,onFeedback = {
            if (visibilityAllowed && !inApp && systemWindowVisible && panel == null)
                speech.show(it,position,petSize,screenArea())
        }).also {
            it.setAutomaticEnabled(behaviorPreferences.automaticBehaviorEnabled(it.automaticByDefault))
            it.setMovementEnabled(behaviorPreferences.automaticMovementEnabled(it.movementByDefault))
        }
    }

    fun preferredSize(pixels: Int): PetSize = animator?.preferredSize(pixels) ?: PetSize(pixels, pixels)
    fun syncPreferences() {
        animator?.setAutomaticEnabled(behaviorPreferences.automaticBehaviorEnabled(true))
        animator?.setMovementEnabled(behaviorPreferences.automaticMovementEnabled(false))
        animator?.refreshSettings()
    }
    fun trimMemory() { animator?.trimMemory() }

    fun screenArea(): ScreenArea = bounds.read(root)

    // Coordinates are physical left/top, independent of locale. handleTouch delegates taps to performClick.
    @SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
    override fun open(position: Position, size: PetSize) {
        check(root == null)
        this.position = position; petSize = size
        val container = FrameLayout(viewContext)
        // INVISIBLE still participates in the first layout; don't draw until safe bounds are known.
        container.visibility = View.INVISIBLE
        root = container
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                PetDiagnostics.event("window.attached", "hasFrame=${pet?.hasFrame}")
                if (root === container) scheduleBoundsRefresh(container)
            }
            override fun onViewDetachedFromWindow(view: View) {
                PetDiagnostics.event("window.detached", "expected=${root !== container} hasFrame=${pet?.hasFrame}")
                if (root === container && !recovering) {
                    layoutReady = false
                    animator?.setVisible(false)
                    // Android clears View.mAttachInfo after this callback returns. Check on the
                    // next main-loop turn so isAttachedToWindow reflects the completed detach.
                    recoveryHandler.post { if (root === container) ensureAttached() }
                }
            }
        })
        checkNotNull(pet).also { view ->
            view.onWindowVisible = { visible -> safely { systemWindowVisible = visible; refreshVisibility() } }
            view.setOnClickListener { safely {
                animator?.onTap()
            } }
            view.setOnLongClickListener { safely { showMenu() }; true }
            view.setOnTouchListener { v, event -> handleTouch(v, event) }
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        params = WindowManager.LayoutParams(size.width, size.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0),
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = position.x; y = position.y
            // This window consumes touch. The passthrough obscuring limit does not apply.
            alpha = 1f
            if (Build.VERSION.SDK_INT >= 30) {
                // Physical display coordinates: disable automatic fitting to avoid double offsets.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            title = "Naiwa floating pet"
        }
        container.setOnApplyWindowInsetsListener { _, insets ->
            scheduleBoundsRefresh(container)
            insets
        }
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleBoundsRefresh(container) }
        manager.addView(container, params)
        windowAdded = true
        PetDiagnostics.event("window.add", "attached=${container.isAttachedToWindow}")
        animator?.resize(size)
        container.requestApplyInsets()
    }

    private fun scheduleBoundsRefresh(container: FrameLayout) {
        if (boundsRefreshPosted) return
        boundsRefreshPosted = true
        container.post {
            boundsRefreshPosted = false
            if (root !== container || !container.isAttachedToWindow || container.width == 0) return@post
            safely {
                val area = screenArea()
                if (!layoutReady || area != lastArea) {
                    lastArea = area
                    animator?.onGeometryChanged()
                    onBoundsChanged(area)
                    if (root === container) {
                        layoutReady = true
                        refreshVisibility()
                    }
                }
            }
        }
    }

    override fun update(position: Position, size: PetSize) {
        if (petSize != size) animator?.onGeometryChanged()
        this.position = position; petSize = size
        applyLayout()
        animator?.resize(size)
    }

    private fun applyLayout() {
        val container = root ?: return
        val p = params ?: return
        val area = screenArea()
        val size = WindowGeometry.fit(petSize, area)
        val clamped = WindowGeometry.clamp(position, size, area)
        p.width = size.width; p.height = size.height
        p.x = clamped.x; p.y = clamped.y
        if (container.isAttachedToWindow) manager.updateViewLayout(container, p) else ensureAttached()
        try { repositionPanel() } catch (error: RuntimeException) { panelFailure(error) }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        safely {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureX = event.rawX; gestureY = event.rawY; gestureTime = event.eventTime
                    gestureCancelled = false
                    longPressed = false
                    cancelLongPress()
                    animator?.onTouchDown()
                    drag.down(event.rawX, event.rawY, currentPosition())
                    longPress = Runnable {
                        if (root != null && view.isAttachedToWindow && visibilityAllowed && systemWindowVisible && !gestureCancelled) {
                            longPressed = true; view.performLongClick()
                        }
                    }.also { view.postDelayed(it,ViewConfiguration.getLongPressTimeout().toLong()) }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(); gestureCancelled = true; drag.finish(); onDragEnd(); animator?.onTouchReleased()
                }
                MotionEvent.ACTION_MOVE -> if (!gestureCancelled) drag.move(event.rawX, event.rawY)?.let {
                    cancelLongPress()
                    val before = currentPosition()
                    onMove(it)
                    val after = currentPosition()
                    animator?.onMovement(after.x - before.x, after.y - before.y)
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    val moved = drag.finish()
                    onDragEnd()
                    animator?.onTouchReleased()
                    if (!gestureCancelled && !longPressed) {
                        if (!moved) view.performClick()
                        else if (com.naiwa.androidpet.companion.SwipeClassifier.isSwipe(event.rawX-gestureX,event.rawY-gestureY,
                                event.eventTime-gestureTime,viewContext.resources.displayMetrics.density)) animator?.onSwipe()
                        else animator?.onDragged()
                    }
                }
            }
        }
        return true
    }

    private fun cancelLongPress() { longPress?.let { pet?.removeCallbacks(it) }; longPress = null }
    fun showControls() { safely { showMenu() } }
    fun refreshConfiguration() {
        if (panel != null) { closePanel(); showMenu() }
    }

    private fun showMenu() {
        if (root == null || panel != null || !visibilityAllowed || !systemWindowVisible) return
        animator?.setPanelOpen(true)
        speech.close()
        val container = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = NativeStyle.surface(viewContext)
            alpha = 1f
        }
        panel = container
        val close = NativeStyle.button(viewContext, R.string.back_to_pet) { safely { closePanel() } }
        panelClose = close
        container.addView(close)
        val body = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        fun heading(label: Int) {
            body.addView(NativeStyle.text(viewContext, label, 17f, true).apply {
                setPadding(0, dp(16), 0, dp(4))
            })
        }
        fun button(label: Int, action: () -> Unit): Button =
            NativeStyle.button(viewContext, label) { safely(action) }.also { body.addView(it) }
        fun action(label: Int, value: PetAction) {
            actionButtons[value] = button(label) {
                if (value == PetAction.WALK) closePanel()
                animator?.play(value)
            }.apply {
                isEnabled = animator?.canManual(value) == true
            }
        }
        heading(R.string.menu_title)
        currentActionLabel = NativeStyle.text(viewContext,R.string.current_action_empty,14f,true).also { body.addView(it) }
        button(R.string.stop_pet, onClose)
        heading(R.string.quick_interaction)
        button(R.string.say_hello) { closePanel(); animator?.onTap() }
        com.naiwa.androidpet.game.GameKind.entries.forEach { game ->
            body.addView(NativeStyle.button(viewContext,game.title) { safely {
                closePanel()
                viewContext.startActivity(com.naiwa.androidpet.game.GameActivity.intent(viewContext,game)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } })
        }
        heading(R.string.automatic_heading)
        fun toggle(label: Int,onChange: (Boolean) -> Unit): Switch = Switch(viewContext).apply {
            setText(label); textSize = 16f; setTextColor(NativeStyle.ink)
            minimumHeight = dp(48); setPadding(0,dp(8),0,dp(8))
            setOnCheckedChangeListener { _, checked -> if (!updatingControls) safely { onChange(checked) } }
            body.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
        automaticSwitch = toggle(R.string.automatic_behavior) {
            behaviorPreferences.setAutomaticBehavior(it); animator?.setAutomaticEnabled(it)
        }
        automaticHint = NativeStyle.text(viewContext,R.string.automatic_waiting).also { body.addView(it) }
        movementSwitch = toggle(R.string.automatic_movement) {
            behaviorPreferences.setAutomaticMovement(it); animator?.setMovementEnabled(it)
        }
        movementHint = NativeStyle.text(viewContext,R.string.movement_waiting).also { body.addView(it) }
        for ((group, title) in listOf(ActionGroup.BASIC to R.string.basic_actions,
            ActionGroup.FUNNY to R.string.funny_actions, ActionGroup.EXTENSION to R.string.more_actions)) {
            val available = PetAction.entries.filter { it.group == group && animator?.canManual(it) == true }
            if (group == ActionGroup.EXTENSION && available.isEmpty()) continue
            heading(title)
            if (available.isEmpty()) body.addView(NativeStyle.text(viewContext, R.string.no_approved_actions))
            else available.forEach { action(it.labelResource(), it) }
        }
        heading(R.string.size_heading)
        button(R.string.size_small) { onResize(80) }
        button(R.string.size_medium) { onResize(120) }
        button(R.string.size_large) { onResize(180) }
        container.addView(ScrollView(viewContext).apply {
            isFillViewport = false; addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val placement = panelPlacement() ?: run { closePanel(); openControlPage(); return }
        panelParams = WindowManager.LayoutParams().apply {
            copyFrom(checkNotNull(params))
            title = "Naiwa controls"; alpha = 1f
            width = placement.size.width; height = placement.size.height
            x = placement.position.x; y = placement.position.y
        }
        manager.addView(container, panelParams)
        panelAdded = true
        animator?.setStateListener { updateState(it) }
        animator?.snapshot?.let(::updateState)
    }

    private fun actionName(action: PetAction?): String = viewContext.getString(action?.labelResource() ?: R.string.action_loading)

    private fun updateState(state: BehaviorSnapshot) {
        updateSelection(state.action)
        val mode = viewContext.getString(if (state.action == PetAction.IDLE) R.string.action_default else when (state.origin) {
            ActionOrigin.MANUAL -> R.string.action_manual
            ActionOrigin.TAP -> R.string.action_tap
            ActionOrigin.GAME -> R.string.action_game
            ActionOrigin.AUTOMATIC -> R.string.action_automatic
            ActionOrigin.MOVEMENT -> R.string.action_movement
            ActionOrigin.MANUAL_MOVEMENT -> R.string.action_manual_movement
            ActionOrigin.DEFAULT -> R.string.action_default
        })
        currentActionLabel?.text = viewContext.getString(R.string.current_action_state,actionName(state.action),mode)
        updatingControls = true
        try {
            automaticSwitch?.isChecked = state.automaticEnabled
            movementSwitch?.isChecked = state.movementEnabled
        } finally { updatingControls = false }
        automaticHint?.text = when {
            !state.automaticEnabled -> viewContext.getString(R.string.automatic_off)
            state.automaticActions.isEmpty() -> viewContext.getString(R.string.automatic_waiting)
            else -> viewContext.getString(R.string.automatic_ready,state.automaticActions.size)
        }
        movementHint?.text = when {
            !state.movementEnabled -> viewContext.getString(R.string.movement_off)
            PetAction.WALK !in state.automaticActions -> viewContext.getString(R.string.movement_blocked,
                state.blockedReasons[PetAction.WALK] ?: viewContext.getString(R.string.movement_waiting))
            state.moving -> viewContext.getString(R.string.movement_active)
            else -> viewContext.getString(R.string.movement_ready)
        }
        actionButtons.forEach { (action,button) -> button.isEnabled = animator?.canManual(action) == true }
    }

    private fun updateSelection(action: PetAction?) {
        actionButtons.forEach { (value, button) ->
            button.isSelected = value == action
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription =
                viewContext.getString(if (button.isSelected) R.string.action_selected else R.string.action_available)
        }
    }

    private fun panelPlacement(): PanelPlacement? {
        val p = params ?: return null
        val area = screenArea()
        val width = minOf(dp(288), area.width)
        val minimumWidth = minOf(dp(200), width)
        // Measure at the narrowest allowed width so side placements and large fonts stay safe.
        panelClose?.measure(View.MeasureSpec.makeMeasureSpec((minimumWidth - dp(24)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val minimumHeight = (panelClose?.measuredHeight ?: dp(48)) + dp(96)
        return PanelGeometry.place(area, Position(p.x, p.y), PetSize(p.width, p.height),
            PetSize(width, maxOf(dp(520), minimumHeight)), PetSize(minimumWidth, minimumHeight), dp(8))
    }

    private fun repositionPanel() {
        val container = panel ?: return
        if (!panelAdded) return
        val placement = panelPlacement() ?: run { closePanel(); openControlPage(); return }
        val p = panelParams ?: return
        p.width = placement.size.width; p.height = placement.size.height
        p.x = placement.position.x; p.y = placement.position.y
        manager.updateViewLayout(container, p)
    }

    private fun openControlPage() {
        Toast.makeText(viewContext, R.string.panel_space_fallback, Toast.LENGTH_LONG).show()
        viewContext.startActivity(Intent(viewContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun closePanel() {
        animator?.setStateListener(null)
        animator?.setPanelOpen(false)
        val container = panel
        val registered = panelAdded
        panel = null; panelParams = null; panelAdded = false; panelClose = null
        automaticSwitch = null; movementSwitch = null; currentActionLabel = null; automaticHint = null; movementHint = null
        actionButtons.clear()
        if (container != null && registered) {
            try { manager.removeViewImmediate(container) }
            catch (error: IllegalArgumentException) { PetDiagnostics.event("panel.already_removed", error = error) }
        }
    }

    fun setVisible(visible: Boolean) {
        visibilityAllowed = visible
        if (!visible) { closePanel(); speech.close() }
        refreshVisibility()
        if (visible) ensureAttached()
    }

    fun setInApp(visible: Boolean) {
        if (inApp == visible) return
        inApp = visible
        if (visible) { speech.close(); closePanel() }
        refreshVisibility()
    }

    /** Event-driven reattachment of the same view; no periodic window replacement. */
    fun ensureAttached() {
        val container = root ?: return
        if (container.isAttachedToWindow || recoveryPending || recoveryBlocked || !visibilityAllowed || !windowAdded) return
        recoveryPending = true
        recoveryHandler.postDelayed({
            recoveryPending = false
            if (root !== container || container.isAttachedToWindow || !visibilityAllowed) return@postDelayed
            val permitted = Settings.canDrawOverlays(viewContext)
            if (!recovery.claim(container.isAttachedToWindow, permitted, root === container)) {
                recoveryBlocked = true
                PetDiagnostics.event("window.recovery_stopped", "permission=$permitted attached=${container.isAttachedToWindow}")
                if (!permitted) onFailure(SecurityException("悬浮窗权限已撤销"))
                else onDiagnostic("悬浮窗口恢复次数已用尽，请在应用中查看运行信息。")
                return@postDelayed
            }
            try {
                recovering = true
                try { manager.removeViewImmediate(container) } catch (_: IllegalArgumentException) { }
                layoutReady = false; systemWindowVisible = true
                container.visibility = View.INVISIBLE
                manager.addView(container, params)
                PetDiagnostics.event("window.readded", "attached=${container.isAttachedToWindow}")
                container.requestApplyInsets()
            } catch (error: RuntimeException) {
                PetDiagnostics.event("window.recovery_failed", error = error)
                if (permitted) ensureAttached()
            } finally { recovering = false }
        }, 400)
    }

    private fun refreshVisibility() {
        val visible = visibilityAllowed && layoutReady && !inApp
        root?.visibility = if (!layoutReady) View.INVISIBLE else if (visible) View.VISIBLE else View.GONE
        if (!visible || !systemWindowVisible) { cancelLongPress(); closePanel() }
        animator?.setVisible(visible && systemWindowVisible)
        if(visible && systemWindowVisible)com.naiwa.androidpet.runtime.PetRuntime.takeGameResult()?.let { message ->
            animator?.gameFeedback(PetAction.HAPPY)
            if(behaviorPreferences.feedbackEnabled)speech.show(message,position,petSize,screenArea())
        }
        val state = "allowed=$visibilityAllowed layout=$layoutReady system=$systemWindowVisible inApp=$inApp attached=${root?.isAttachedToWindow} frame=${pet?.hasFrame}"
        if (state != lastVisibility) { lastVisibility = state; PetDiagnostics.event("window.visibility", state) }
    }

    override fun close() {
        speech.close()
        recoveryHandler.removeCallbacksAndMessages(null); recoveryPending = false
        cancelLongPress()
        val container = root
        val registered = windowAdded
        pet?.onWindowVisible = null
        root = null; windowAdded = false; params = null; lastArea = null
        visibilityAllowed = false; layoutReady = false; boundsRefreshPosted = false
        try {
            closePanel()
        } finally {
            try { animator?.close() } finally {
                animator = null
                pet?.clearFrame(); pet = null
                if (container != null) {
                    container.setOnApplyWindowInsetsListener(null)
                    container.removeAllViews()
                    // Registration precedes View attachment. A rapid STOP removes both states.
                    if (registered) {
                        try { manager.removeViewImmediate(container) }
                        catch (error: IllegalArgumentException) { PetDiagnostics.event("window.already_removed", error = error) }
                        PetDiagnostics.event("window.remove")
                    }
                }
            }
        }
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (error: RuntimeException) {
            try { closePanel() } catch (cleanup: RuntimeException) { error.addSuppressed(cleanup) }
            if (error is SecurityException) onFailure(error)
            else {
                PetDiagnostics.event("overlay.operation_failed", "attached=${root?.isAttachedToWindow} frame=${pet?.hasFrame}", error)
                onDiagnostic("操作未完成，奶蛙已保留：${error.javaClass.simpleName}")
                ensureAttached()
            }
        }
    }
    private fun panelFailure(error: RuntimeException) {
        PetDiagnostics.event("panel.operation_failed", error = error)
        try { closePanel() } catch (cleanup: RuntimeException) { error.addSuppressed(cleanup) }
        onDiagnostic("面板操作未完成，奶蛙仍在运行。")
    }
    private fun dp(value: Int) = (value * viewContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
