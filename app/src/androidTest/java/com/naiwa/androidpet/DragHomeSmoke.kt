package com.naiwa.androidpet

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.behavior.HorizontalMoveHost
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.overlay.*
import com.naiwa.androidpet.ui.*
import java.io.File

/** Only this round's real HOME, drag, facing and speech checks on the isolated emulator. */
object DragHomeSmoke {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var output: File
    private lateinit var instrumentation: Instrumentation
    private var sessionToClose: OverlaySession? = null
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun later(delay: Long = 0, block: () -> Unit) { handler.postDelayed({
        try { block() } catch (error: Throwable) { finish(error) }
    }, delay) }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    private fun finish(error: Throwable? = null) {
        handler.removeCallbacksAndMessages(null)
        sessionToClose?.close(); sessionToClose=null
        instrumentation.finish(if (error==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", error?.stackTraceToString() ?: "DRAG_HOME_SMOKE_SUCCESS\n") })
    }
    private fun await(deadline: Long = SystemClock.uptimeMillis()+6000, condition: () -> Boolean, done: () -> Unit) {
        if (condition()) done() else {
            check(SystemClock.uptimeMillis() < deadline) { "Required native state did not appear" }
            later(30) { await(deadline, condition, done) }
        }
    }
    private fun draw(view: View, name: String): Bitmap = Bitmap.createBitmap(
        view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        view.draw(Canvas(bitmap))
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun run(instrumentation: Instrumentation) {
        this.instrumentation=instrumentation
        try {
            val context=instrumentation.targetContext
            output=File(context.filesDir,"drag-home-smoke").also { it.mkdirs() }
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            later { home(context,activity) }
            later(15_000) { error("Required round checks timed out") }
        } catch (error: Throwable) { finish(error) }
    }

    private fun home(context: Context, screen: Activity) {
        val ui=field<CompanionUi>(screen,"ui")
        val preview=field<PetPreview>(ui, "preview")
        val frame=field<PetFrameView>(preview, "frame")
        val root=screen.window.decorView as ViewGroup
        if (!frame.hasFrame || root.width==0) { later(40) { home(context,screen) }; return }
        draw(root, "home").recycle()
        fun label(text: String)=all(root).filterIsInstance<TextView>().single { it.text.toString()==text }
        fun bounds(view: View)=Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
        val petBounds=bounds(preview)
        val hello=label("打个招呼")
        val play=label("去玩一局")
        val care=label("看看养成记忆")
        val scroll=field<ViewGroup>(ui, "scroll")
        check(hello.parent===preview.parent && (play.parent as View).parent===preview.parent)
        check(bounds(hello).top-petBounds.bottom < NativeStyle.dp(context, 112))
        check(bounds(care).bottom<=bounds(scroll).bottom) { "HOME interactions require scrolling" }
        check(all(root).count { it.contentDescription=="动作图鉴" }==1)
        check(all(root).count { it.contentDescription=="设置" }==1)
        check(label("开启桌宠").isEnabled)
        val animator=field<PetAnimator>(preview,"animator")
        check(hello.performClick()); check(animator.currentAction!=PetAction.IDLE)
        play.performClick(); check(ui.page==AppPage.PLAY)
        ui.show(AppPage.HOME)
        all(root).filterIsInstance<TextView>().single { it.text.toString()=="看看养成记忆" }.performClick()
        check(ui.page==AppPage.CARE); screen.finish()
        report("PASS HOME: real native layout, adjacent visible shortcuts, greeting/game/memory actions")
        later(150) { overlay(context) }
    }

    private fun overlay(context: Context) {
        lateinit var window: OverlayWindowController
        lateinit var session: OverlaySession
        val store=object: OverlayStore { override var position: Position?=Position(300, 650); override var sizeDp=120 }
        window=OverlayWindowController(context, { session.position }, { session.moveTo(it) },
            { session.savePosition() }, { session.close() }, {}, { session.updateArea(it) }, { error(it) }, { throw it })
        val animator=field<PetAnimator>(window, "animator")
        val pet=field<PetFrameView>(window, "pet")
        animator.setAutomaticEnabled(false); animator.setMovementEnabled(false)
        session=OverlaySession(window, store)
        sessionToClose=session
        check(session.start(window.screenArea(), window.preferredSize(240), true)); window.setVisible(true)
        val root=field<View>(window, "root")
        val speech=field<SpeechBubbleWindow>(window, "speech")
        await(condition={ root.isAttachedToWindow && root.visibility==View.VISIBLE && pet.hasFrame }) {
            report("PASS overlay opens with a real WindowManager window and decoded PNG")
            // Begin from a one-shot action: dragging must still turn the complete character.
            check(animator.play(PetAction.WAVE))
            val size=field<PetSize>(window, "petSize")
            speech.show("一起慢慢走。", session.position, size, window.screenArea())
            val label=checkNotNull(field<TextView?>(speech, "bubble"))
            val bubbleParams=field<WindowManager.LayoutParams>(speech, "params")
            val firstPosition=session.position
            val firstBubble=Position(bubbleParams.x, bubbleParams.y)
            val shownAt=SystemClock.uptimeMillis()
            val down=shownAt
            fun touch(action: Int, x: Float, duration: Long=SystemClock.uptimeMillis()-down) {
                val event=MotionEvent.obtain(down, down+duration, action, x, 100f, 0)
                try { pet.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_MOVE, 40f)
            check(animator.currentAction==PetAction.WALK && animator.snapshot.origin==ActionOrigin.DRAG)
            check(animator.currentDirection==PetDirection.LEFT && animator.displayedDirection==PetDirection.LEFT)
            check(bubbleParams.x-firstBubble.x==session.position.x-firstPosition.x)
            check(bubbleParams.y-firstBubble.y==session.position.y-firstPosition.y)
            later(180) {
                check(pet.mirrored)
                val first=draw(pet, "drag-left-step-a")
                later(170) {
                    val second=draw(pet, "drag-left-step-b")
                    var legChanges=0
                    for (y in pet.height*2/3 until pet.height) for (x in 0 until pet.width)
                        if (first.getPixel(x,y)!=second.getPixel(x,y)) legChanges++
                    check(legChanges>50) { "Drag legs stayed static" }; first.recycle()
                    val player=field<AnimationController>(animator, "controller")
                    val index=field<Int>(player, "index")
                    val bitmap=field<Bitmap>(pet, "frame")
                    touch(MotionEvent.ACTION_MOVE, 160f)
                    check(animator.displayedDirection==PetDirection.RIGHT && !pet.mirrored)
                    check(field<Int>(player,"index")==index && field<Bitmap>(pet,"frame")===bitmap)
                    val right=draw(pet, "drag-right-same-step")
                    var headPixels=0; var matched=0
                    for (y in 0 until pet.height/2) for (x in 0 until pet.width) {
                        val leftPixel=second.getPixel(pet.width-1-x,y)
                        if (leftPixel ushr 24>32) {
                            headPixels++; if (leftPixel==right.getPixel(x,y)) matched++
                        }
                    }
                    check(headPixels>100 && matched>headPixels*.95) { "Head did not mirror with body" }
                    second.recycle(); right.recycle()
                    check(field<TextView>(speech,"bubble")===label && label.text.toString()=="一起慢慢走。")
                    check(bubbleParams.x-firstBubble.x==session.position.x-firstPosition.x)
                    check(bubbleParams.y-firstBubble.y==session.position.y-firstPosition.y)
                    touch(MotionEvent.ACTION_UP, 160f, 1500)
                    later(200) {
                        check(animator.currentAction==PetAction.IDLE && !pet.mirrored)
                        check(store.position==session.position)
                        check(bubbleParams.x-firstBubble.x==session.position.x-firstPosition.x)
                        report("PASS drag: moving legs, whole-head/body left/right mirror, retained gait phase, release to IDLE")
                        later((shownAt+1950-SystemClock.uptimeMillis()).coerceAtLeast(0)) {
                            check(field<TextView?>(speech,"bubble")==null) { "Following extended speech expiry" }
                            report("PASS speech: real window follows both directions, stable offset/text, original auto-dismiss")
                            finish()
                        }
                    }
                }
            }
        }
    }
}
