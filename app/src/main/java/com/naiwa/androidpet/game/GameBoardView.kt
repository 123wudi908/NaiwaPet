package com.naiwa.androidpet.game

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import com.naiwa.androidpet.animation.PetAction
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.ui.*

/** Only exists inside GameActivity. It never creates an overlay or a full-screen touch window. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic view requires a real session, never an XML placeholder game.
class GameBoardView(context: Context, var session: GameSession, private val onChanged: () -> Unit) : FrameLayout(context) {
    private val preferences = PetPreferences(context)
    private val sound = FeedbackPlayer(context)
    private val field = Field(context)
    private val pet = PetPreview(context,interactive=false,remember=false)
    private var active = false
    private var posted = false
    private var lastFrame = 0L
    private var lastUi = 0L
    private var lastPose = 0L
    private var lastScore = 0
    private var petSide = 1
    private var hitAt = -1000L
    var feedbackText = ""; private set
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            if (!active || session.phase != GamePhase.RUNNING) return
            val now = SystemClock.uptimeMillis()
            session.advance((now-lastFrame).coerceAtLeast(0)); lastFrame = now
            if (session.score > lastScore && session.kind == GameKind.BUBBLES) hit(HitFeedback.GOOD)
            lastScore = session.score
            updatePetPosition(); field.invalidate()
            if (now-lastUi >= 100 || session.phase == GamePhase.FINISHED) { lastUi = now; onChanged() }
            postFrame()
        }
    }
    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isClickable = true
        background = NativeStyle.surface(context,NativeStyle.cream,NativeStyle.Radius.stage)
        clipToOutline = true
        addView(field,LayoutParams(-1,-1))
        addView(pet,LayoutParams(1,1,Gravity.BOTTOM or Gravity.START))
        contentDescription = "游戏操作区：${session.kind.description}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec)
        val h=MeasureSpec.getSize(heightMeasureSpec)
        val side=minOf((w*.78).toInt(),dp(if(session.kind==GameKind.BUBBLES)132 else 270),h-dp(8)).coerceAtLeast(1)
        // Set child size before FrameLayout measures it. Changing it in onSizeChanged is
        // too late for that layout pass and can strand the preview at its initial 1x1.
        if(side!=petSide){
            petSide=side
            pet.layoutParams=LayoutParams(side,side,Gravity.BOTTOM or Gravity.START).apply{bottomMargin=dp(4)}
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        super.onLayout(changed,left,top,right,bottom)
        updatePetPosition()
    }

    fun sync() {
        Choreographer.getInstance().removeFrameCallback(frameCallback); posted = false
        lastFrame = SystemClock.uptimeMillis(); lastScore = session.score
        // A recreated PAUSED screen still needs its first visible character frame.
        // Only the game clock/targets stop; the visible preview can breathe at idle.
        pet.setActive(active)
        pet.refreshSettings(); field.invalidate(); updatePetPosition(); postFrame()
        if (session.phase == GamePhase.FINISHED) pet.gameFeedback(PetAction.HAPPY)
    }
    fun setActive(value: Boolean) { active = value; sync() }
    private fun postFrame() {
        if (!posted && active && session.phase == GamePhase.RUNNING) {
            posted = true; Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }
    private fun updatePetPosition() {
        val center = if (session.kind == GameKind.BUBBLES) session.playerX*width else width/2f
        pet.translationX = (center-petSide/2f).coerceIn(0f,(width-petSide).coerceAtLeast(0).toFloat())
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = active && session.phase==GamePhase.RUNNING
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!active || session.phase!=GamePhase.RUNNING)return false
        if (event.pointerCount > 1) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (session.kind == GameKind.BUBBLES) session.moveTo(event.x/width.coerceAtLeast(1))
                else if (active) hit(session.tap())
                updatePetPosition(); field.invalidate(); onChanged()
            }
            MotionEvent.ACTION_MOVE -> if (active && session.kind == GameKind.BUBBLES) {
                session.moveTo(event.x/width.coerceAtLeast(1)); updatePetPosition(); field.invalidate()
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun hit(result: HitFeedback) {
        if (result == HitFeedback.IGNORED) return
        feedbackText = when (result) { HitFeedback.PERFECT -> "正好！"; HitFeedback.GOOD -> "接住节奏了"; else -> "慢一点，等下一拍" }
        hitAt = SystemClock.uptimeMillis()
        sound.play(result != HitFeedback.MISS)
        if (result != HitFeedback.MISS) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (hitAt-lastPose >= 550) {
                lastPose = hitAt
                pet.gameFeedback(when {
                    session.kind == GameKind.DRUM -> PetAction.BELLY_DRUM
                    session.kind == GameKind.BUBBLES -> PetAction.HAPPY
                    session.stage >= 3 -> PetAction.LAUGH_FALL
                    session.stage == 2 -> PetAction.DANCE
                    else -> PetAction.WOBBLE
                })
            }
            if (preferences.animationsEnabled) pet.animate().scaleX(.97f).scaleY(.97f).setDuration(60)
                .withEndAction { pet.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }.start()
        }
        field.invalidate()
    }
    fun close() {
        active = false; Choreographer.getInstance().removeFrameCallback(frameCallback); posted = false
        pet.animate().cancel(); pet.close(); sound.close()
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
    private fun dp(value: Int) = NativeStyle.dp(context,value)

    private inner class Field(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = NativeStyle.border
            canvas.drawLine(dp(24).toFloat(),height-dp(12).toFloat(),width-dp(24).toFloat(),height-dp(12).toFloat(),paint)
            when (session.kind) {
                GameKind.DRUM -> {
                    val phase = (((session.elapsedMillis-1000)%600+600)%600)/600f
                    val cx = width/2f; val cy = height-petSide*.43f-dp(4)
                    paint.color = NativeStyle.accent; paint.strokeWidth = dp(2).toFloat()
                    canvas.drawCircle(cx,cy,dp(14).toFloat(),paint)
                    paint.alpha = 120
                    canvas.drawCircle(cx,cy,dp(20)+dp(52)*(1-phase),paint);paint.alpha = 255
                }
                GameKind.BUBBLES -> {
                    session.bubbles.forEach { bubble ->
                        val x = bubble.x*width
                        val y = ((session.elapsedMillis-bubble.bornAt)/2600f)*height
                        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                        canvas.drawCircle(x,y,dp(10).toFloat(),paint)
                        paint.style = Paint.Style.STROKE;paint.strokeWidth = dp(2).toFloat();paint.color = NativeStyle.accent
                        canvas.drawCircle(x,y,dp(10).toFloat(),paint)
                        paint.color = NativeStyle.border;canvas.drawArc(x-dp(6).toFloat(),y-dp(6).toFloat(),x+dp(6).toFloat(),y+dp(6).toFloat(),200f,60f,false,paint)
                    }
                }
                GameKind.COMBO -> {
                    paint.color = NativeStyle.border; paint.style = Paint.Style.STROKE;paint.strokeWidth = dp(2).toFloat()
                    val count = session.stage+2
                    repeat(count) { i -> canvas.drawLine(dp(22+i*14).toFloat(),dp(28).toFloat(),dp(22+i*14).toFloat(),dp(40+i%2*10).toFloat(),paint) }
                }
            }
            val age = SystemClock.uptimeMillis()-hitAt
            if (preferences.animationsEnabled && age in 0..300) {
                paint.style = Paint.Style.STROKE;paint.color = NativeStyle.accent;paint.alpha = ((1-age/300f)*160).toInt()
                canvas.drawCircle(width/2f,height-petSide*.43f,dp(20)+dp(50)*(age/300f),paint);paint.alpha = 255
            }
        }
    }
}
