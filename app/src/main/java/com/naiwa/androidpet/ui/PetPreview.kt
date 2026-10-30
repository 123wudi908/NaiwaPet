package com.naiwa.androidpet.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.Gravity
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.behavior.*
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.overlay.PetFrameView

/** Same approved renderer as the overlay; one preview and one decode queue per visible screen. */
class PetPreview @JvmOverloads constructor(context: Context, private val interactive: Boolean = true,
    private val remember: Boolean = true, private val feedback: (String) -> Unit = {}) : FrameLayout(context) {
    private val frame = PetFrameView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var animator: PetAnimator? = null
    private var active = false
    private var closed = false
    var stateListener: ((BehaviorSnapshot) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    private var requestedAction: PetAction? = null
    init {
        addView(frame,LayoutParams(-1,-1))
        if (interactive) {
            frame.setOnClickListener { animator?.onTap() }
            frame.setOnLongClickListener { onLongPress?.invoke(); true }
        } else { frame.isClickable = false; frame.isLongClickable = false }
        contentDescription = "奶蛙角色预览"
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            if (closed || animator != null || !isAttachedToWindow) return@post
            val host = object : HorizontalMoveHost {
                override var position = Position(0,0)
                override val size get() = PetSize(width.coerceAtLeast(1),height.coerceAtLeast(1))
                override val area get() = ScreenArea(0,0,width.coerceAtLeast(1),height.coerceAtLeast(1))
                override fun moveTo(position: Position) { this.position = position }
                override fun savePosition() { }
            }
            animator = PetAnimator(context,frame,host,{ feedback(it) },feedback,remember).also {
                it.setAutomaticEnabled(false); it.setMovementEnabled(false)
                it.setStateListener { state -> stateListener?.invoke(state) }
                it.resize(PetSize(width.coerceAtLeast(1),height.coerceAtLeast(1)))
                it.setVisible(active)
            }
            requestedAction?.let(::play)
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (w > 0 && h > 0) animator?.resize(PetSize(w,h))
    }
    fun setActive(value: Boolean) { active = value; if (!closed) animator?.setVisible(value) }
    fun refreshSettings() { animator?.refreshSettings() }
    fun greet(): Boolean = animator?.onTap() == true
    fun play(action: PetAction): Boolean {
        requestedAction = action
        handler.removeCallbacksAndMessages(null)
        val a = animator ?: return false
        if (action != PetAction.WALK) return a.play(action)
        val started = a.gameFeedback(action)
        if (started) handler.postDelayed({ if (!closed) a.finishPreviewWalk() },1400)
        return started
    }
    fun gameFeedback(action: PetAction): Boolean {
        val accepted = animator?.gameFeedback(action) == true
        if (accepted) handler.removeCallbacksAndMessages(null)
        return accepted
    }
    fun close() {
        if (closed) return
        closed = true; handler.removeCallbacksAndMessages(null)
        stateListener = null; animator?.close(); animator = null
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
