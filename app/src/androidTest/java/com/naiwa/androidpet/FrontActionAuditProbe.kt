package com.naiwa.androidpet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.overlay.*
import java.io.File
import kotlin.math.abs

/** Front-only audit, deliberately isolated from the existing direction feature. Test APK only. */
class FrontActionAuditProbe(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.filesDir, "front-action-audit").also { it.mkdirs() }
    private var passed = 0
    private val failures = mutableListOf<String>()

    fun run() {
        com.naiwa.androidpet.runtime.PetDiagnostics.initialize(context)
        case("WALK body root aligned without scaling, clipping or cached IDLE offset leakage") { placement() }
        case("all 14 front actions / 86 frames, fixed window, return to IDLE, drag resize and close") { playbackAndWindow() }
        instrumentation.finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("passed", passed); putInt("failed", failures.size)
            putString("stream", "\nFront action audit: $passed passed, ${failures.size} failed\n" + failures.joinToString("\n"))
        })
    }
    private fun case(name: String, block: () -> Unit) {
        try { block(); passed++; status("PASS $name") }
        catch (error: Throwable) { failures += "$name\n${error.stackTraceToString()}"; status("FAIL $name") }
    }
    private fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }
    private fun until(timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            var ready = false; main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        error("Native condition timed out after ${timeout}ms")
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    private fun replace(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun render(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun save(name: String, bitmap: Bitmap) = File(output, "$name.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    private fun present(sink: FrameSink, path: String) {
        var complete = false; var failure: Throwable? = null
        main { sink.present(path) { failure = it.exceptionOrNull(); complete = true } }
        until { complete }; failure?.let { throw it }
    }
    private fun bodyX(bitmap: Bitmap): Double {
        var sum = 0L; var count = 0L
        for (y in 280 until 440) for (x in 0 until 640) if (bitmap.getPixel(x, y) ushr 24 >= 220) { sum += x; count++ }
        check(count > 1000)
        return sum.toDouble() / count
    }
    private fun placement() {
        lateinit var catalog: AssetAnimationCatalog
        lateinit var view: PetFrameView
        lateinit var sink: PngFrameSink
        main {
            catalog = AssetAnimationCatalog.load(context.assets)
            check(catalog.diagnostics.isEmpty()) { catalog.diagnostics.joinToString() }
            view = PetFrameView(context); view.layout(0, 0, 640, 640)
            sink = PngFrameSink(context.assets, catalog.layout, view, catalog.frameLayouts); sink.resize(640)
        }
        try {
            val measured = mutableListOf<String>()
            for (path in catalog.clips.getValue(PetAction.WALK).frames) {
                present(sink, path)
                main {
                    val result = render(view)
                    val center = bodyX(result)
                    measured += "$path,$center"
                    check(center in 318.0..324.0) { "$path body root x=$center; IDLE is x=319..321 on a 640px canvas" }
                    result.recycle()
                }
            }
            // Decoder resolutions and a letterboxed View must apply the same source-space placement.
            for ((width, height, target, shift) in listOf(listOf(640, 640, 640, 38), listOf(320, 320, 320, 19), listOf(480, 320, 320, 19))) {
                lateinit var walkBeforeCache: IntArray
                main { view.layout(0, 0, width, height); sink.resize(target) }
                present(sink, "naiwa/idle/frame01.png")
                lateinit var idleBefore: Bitmap
                main { idleBefore = render(view) }
                present(sink, "naiwa/walk/frame01.png")
                main {
                    val raw = field<Bitmap>(view, "frame")
                    val reference = PetFrameView(context).apply { layout(0, 0, width, height); setFrame(raw) }
                    val before = render(reference); val after = render(view)
                    val original = pixels(before); val adjusted = pixels(after)
                    for (y in 0 until height) for (x in 0 until width) {
                        val expected = if (x >= shift) original[y * width + x - shift] else 0
                        check(adjusted[y * width + x] == expected) { "Placement distorted pixels at $width x $height ($x,$y)" }
                    }
                    check(original.count { it ushr 24 > 0 } == adjusted.count { it ushr 24 > 0 }) { "Placement clipped anatomy" }
                    walkBeforeCache = adjusted
                    save("walk-corrected-${width}x$height", after)
                    before.recycle(); after.recycle()
                }
                present(sink, "naiwa/walk/frame01.png") // Cache hit must retain the calibration.
                main {
                    val afterCache = render(view)
                    check(pixels(afterCache).contentEquals(walkBeforeCache)) { "Cached WALK lost its placement" }
                    save("walk-cached-${width}x$height", afterCache); afterCache.recycle()
                }
                present(sink, "naiwa/idle/frame01.png") // IDLE was cached before WALK.
                main {
                    val idleAfter = render(view)
                    check(idleAfter.sameAs(idleBefore)) { "WALK placement leaked into cached IDLE" }
                    idleBefore.recycle(); idleAfter.recycle()
                }
            }
            File(output, "walk-body-root.csv").writeText("frame,correctedTorsoCenterX\n" + measured.joinToString("\n") + "\n")
        } finally { main { sink.close() } }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun playbackAndWindow() {
        check(Settings.canDrawOverlays(context))
        // A plain Activity behind the overlay makes compositor residue comparison deterministic.
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main { activity.setContentView(View(activity).apply { setBackgroundColor(0xffe8ede4.toInt()) }) }
        lateinit var window: OverlayWindowController
        lateinit var session: OverlaySession
        lateinit var animator: PetAnimator
        lateinit var controller: AnimationController
        lateinit var sink: PngFrameSink
        lateinit var pet: PetFrameView
        lateinit var root: View
        lateinit var catalog: AssetAnimationCatalog
        val diagnostics = mutableListOf<String>()
        val store = object : OverlayStore { override var position: Position? = Position(180, 400); override var sizeDp = 120 }
        val trace = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val geometry = mutableListOf<List<Int>>()
        var lastPresentedPath = ""
        fun windowGeometry(): List<Int> = field<WindowManager.LayoutParams>(window, "params").let { listOf(it.x, it.y, it.width, it.height, pet.width, pet.height) }
        main {
            catalog = AssetAnimationCatalog.load(context.assets)
            window = OverlayWindowController(context, { session.position }, { session.moveTo(it) }, { session.savePosition() },
                { session.close() }, { session.resize(window.preferredSize((it * context.resources.displayMetrics.density).toInt()), it) },
                { session.updateArea(it) }, diagnostics::add, { throw it })
            animator = field(window, "animator"); pet = field(window, "pet")
            animator.setAutomaticEnabled(false); animator.setMovementEnabled(false)
            controller = field(animator, "controller"); sink = field(animator, "sink")
            session = OverlaySession(window, store)
            check(session.start(window.screenArea(), PetSize(320, 320), true))
            window.setVisible(true); root = field(window, "root")
        }
        try {
            until { root.isAttachedToWindow && pet.hasFrame && pet.width == 320 }
            lateinit var baseline: List<Int>
            main {
                baseline = windowGeometry()
                replace(controller, "sink", object : FrameSink {
                    override fun present(path: String, completion: (Result<Unit>) -> Unit) = sink.present(path) { result ->
                        if (result.isSuccess) {
                            lastPresentedPath = path
                            trace += "${SystemClock.uptimeMillis()},$path,${windowGeometry().joinToString(",")}"
                            geometry += windowGeometry()
                            if (seen.add(path)) {
                                val bitmap = render(pet)
                                check(pixels(bitmap).count { it ushr 24 > 0 } > 1000) { "Invisible $path" }
                                save(path.removePrefix("naiwa/").replace('/', '-').removeSuffix(".png"), bitmap); bitmap.recycle()
                            }
                        }
                        completion(result)
                    }
                    override fun cancelPending() = sink.cancelPending()
                    override fun clear() = sink.clear()
                    override fun close() = sink.close()
                })
            }
            check(catalog.clips.size == 14)
            for ((action, clip) in catalog.clips) {
                main {
                    check(controller.currentDirection == PetDirection.FRONT)
                    // WALK is played in place for this audit only, avoiding an intentional window move or side IDLE.
                    check(if (action == PetAction.WALK) controller.request(action, ActionOrigin.MANUAL) else animator.play(action))
                }
                until(12_000) { clip.frames.all(seen::contains) }
                if (clip.loop) main { check(controller.play(PetAction.IDLE)) }
                until(12_000) { controller.currentAction == PetAction.IDLE && lastPresentedPath.startsWith("naiwa/idle/") }
                main {
                    check(geometry.all { it == baseline }) { "Action $action changed window dimensions/position: ${geometry.filter { it != baseline }}" }
                    check(controller.displayedDirection == PetDirection.FRONT && root.isAttachedToWindow)
                }
            }
            check(seen.size == 86) { "Expected all original 86 frames, saw ${seen.size}" }
            File(output, "front-playback.csv").writeText("uptimeMillis,frame,x,y,windowWidth,windowHeight,viewWidth,viewHeight\n" + trace.joinToString("\n") + "\n")
            // Compare real composed pixels, including transparency outside the former raised hand.
            main { controller.pause() }
            present(sink, "naiwa/idle/frame01.png"); SystemClock.sleep(250)
            lateinit var before: Bitmap
            fun screenshot(): Bitmap {
                val location = IntArray(2); main { pet.getLocationOnScreen(location) }
                val full = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                return Bitmap.createBitmap(full, location[0], location[1], pet.width, pet.height).also { full.recycle() }
            }
            before = screenshot()
            present(sink, "naiwa/wave/frame03.png"); SystemClock.sleep(250)
            val raised = screenshot(); save("compositor-wave", raised); raised.recycle()
            present(sink, "naiwa/idle/frame01.png"); SystemClock.sleep(250)
            val after = screenshot(); save("compositor-idle-after-wave", after)
            check(after.sameAs(before)) { "Compositor retained pixels after WAVE -> IDLE" }
            before.recycle(); after.recycle()
            main { controller.resume() }
            main {
                val old = session.position
                val time = SystemClock.uptimeMillis()
                for ((action, at, y) in listOf(listOf(MotionEvent.ACTION_DOWN, 0, 100), listOf(MotionEvent.ACTION_MOVE, 500, 160), listOf(MotionEvent.ACTION_UP, 1000, 160))) {
                    val event = MotionEvent.obtain(time, time + at, action, 140f, y.toFloat(), 0)
                    try { pet.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                check(abs(session.position.y - old.y) >= 50) { "Dragging did not move overlay" }
                check(store.position == session.position)
                check(pet.performClick())
            }
            until(8000) { controller.currentAction == PetAction.IDLE }
            main { window.showControls() }
            until { field<View?>(window, "panel")?.isAttachedToWindow == true }
            main {
                val panel = field<View>(window, "panel")
                views(panel).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.size_small) }.performClick()
                check(store.sizeDp == 80 && root.isAttachedToWindow)
                views(panel).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.stop_pet) }.performClick()
            }
            until { !root.isAttachedToWindow && !session.isOpen }
            check(diagnostics.isEmpty()) { diagnostics.joinToString() }
        } finally { main { session.close(); window.close(); activity.finish() } }
    }
}
