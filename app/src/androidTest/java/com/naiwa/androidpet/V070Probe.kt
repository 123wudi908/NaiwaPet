package com.naiwa.androidpet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.behavior.*
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.overlay.*
import com.naiwa.androidpet.ui.AppPage
import com.naiwa.androidpet.ui.CompanionUi

/** Integration probes packaged in the test APK only. Never runs against a user's phone. */
class V070Probe(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val output get() = java.io.File(context.filesDir, "v070-probe").also { it.mkdirs() }
    private var passed = 0
    private val failures = mutableListOf<String>()

    fun run() {
        com.naiwa.androidpet.runtime.PetDiagnostics.initialize(context)
        case("adaptive launcher and installed 0.7.0 / 7") { iconAndVersion() }
        case("actual settings shows current public version") { settingsVersion() }
        case("eight drag headings, autonomous turns, old actions, resize and close") { overlayDirections() }
        instrumentation.finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("passed", passed); putInt("failed", failures.size)
            putString("stream", "\nV070 probes: $passed passed, ${failures.size} failed\n" + failures.joinToString("\n"))
        })
    }
    private fun case(name: String, block: () -> Unit) {
        try { block(); passed++; instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") }) }
        catch (error: Throwable) { failures += "$name\n${error.stackTraceToString()}"; instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FAIL $name\n") }) }
    }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun until(timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            var done = false; main { done = condition() }
            if (done) return
            SystemClock.sleep(40)
        }
        error("Native condition timed out after ${timeout}ms")
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    private fun replace(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun save(name: String, bitmap: Bitmap) {
        java.io.File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun iconAndVersion() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        check(info.versionName == "0.7.0" && com.naiwa.androidpet.update.ApkIdentity.version(info) == 7L)
        main {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            check(icon is AdaptiveIconDrawable && icon.foreground != null && icon.background != null)
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 192, 192); icon.draw(Canvas(bitmap))
            check(bitmap.getPixel(96, 96) ushr 24 == 255)
            save("launcher-native", bitmap); bitmap.recycle()
        }
    }

    private fun settingsVersion() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { field<CompanionUi>(activity, "ui").show(AppPage.SETTINGS) }
            until { views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "当前版本 v0.7.0" } }
            main {
                val text = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                check(text.none { it.contains("versionCode") || it.contains("-personal") })
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { save("settings-native", it); it.recycle() }
        } finally { main { activity.finish() } }
    }

    private fun overlayDirections() {
        lateinit var window: OverlayWindowController
        lateinit var session: OverlaySession
        lateinit var animator: PetAnimator
        lateinit var pet: PetFrameView
        lateinit var root: View
        val diagnostics = mutableListOf<String>()
        val store = object : OverlayStore { override var position: Position? = Position(300, 500); override var sizeDp = 120 }
        main {
            val catalog = AssetAnimationCatalog.load(context.assets)
            check(catalog.diagnostics.isEmpty()) { catalog.diagnostics.joinToString() }
            check(catalog.directionalClips.keys == PetDirection.entries.filter { it != PetDirection.FRONT }.toSet())
            window = OverlayWindowController(context, { session.position }, { session.moveTo(it) },
                { session.savePosition() }, { session.close() },
                { session.resize(window.preferredSize((it * context.resources.displayMetrics.density).toInt()), it) },
                { session.updateArea(it) }, diagnostics::add, { throw it })
            animator = field(window, "animator"); pet = field(window, "pet")
            animator.setAutomaticEnabled(false); animator.setMovementEnabled(false)
        }
        // Session is deliberately created after its callbacks, as in the production service.
        main {
            session = OverlaySession(window, store)
            check(session.start(window.screenArea(), window.preferredSize(220), true))
            window.setVisible(true); root = field(window, "root")
        }
        try {
            until { root.isAttachedToWindow && root.visibility == View.VISIBLE && pet.hasFrame }
            val vectors = listOf(0 to 100, -100 to 100, -100 to 0, -100 to -100,
                0 to -100, 100 to -100, 100 to 0, 100 to 100)
            for ((index, vector) in vectors.withIndex()) {
                val direction = PetDirection.entries[index]
                var old: Bitmap? = null
                main {
                    val area = window.screenArea()
                    session.moveTo(Position(area.left + area.width / 2 - 150, area.top + area.height / 2 - 150))
                    check(animator.play(PetAction.IDLE))
                    old = field(pet, "frame")
                    val time = SystemClock.uptimeMillis()
                    for ((action, at, x, y) in listOf(
                        listOf(MotionEvent.ACTION_DOWN, 0, 300, 400),
                        listOf(MotionEvent.ACTION_MOVE, 500, 300 + vector.first, 400 + vector.second),
                        listOf(MotionEvent.ACTION_UP, 1000, 300 + vector.first, 400 + vector.second))) {
                        val event = MotionEvent.obtain(time, time + at, action, x.toFloat(), y.toFloat(), 0)
                        try { pet.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    check(animator.currentDirection == direction) { "Drag $direction yielded ${animator.currentDirection}" }
                    check(animator.displayedDirection == direction)
                    check(store.position == session.position)
                }
                until { pet.hasFrame && field<Bitmap?>(pet, "frame") !== old }
                main {
                    val bitmap = Bitmap.createBitmap(pet.width, pet.height, Bitmap.Config.ARGB_8888)
                    pet.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    check(pixels.count { it ushr 24 > 0 } > 1000)
                    save("direction-${direction.directory}", bitmap); bitmap.recycle()
                }
            }
            // Test-only acceleration of the existing scheduler, with no production setting change.
            lateinit var movement: AutoMoveController
            main {
                val interaction = field<PetInteractionController>(animator, "interaction")
                movement = field(interaction, "movement")
                val settings = field<BehaviorSettings>(movement, "settings")
                replace(movement, "settings", settings.copy(moveMinMillis = 1000, moveMaxMillis = 1000))
                replace(movement, "random", BehaviorRandom { 0 })
                animator.setMovementEnabled(true)
            }
            until(12_000) { animator.snapshot.moving && animator.currentDirection == PetDirection.LEFT && animator.snapshot.origin == ActionOrigin.MOVEMENT }
            main {
                animator.setMovementEnabled(false)
                replace(movement, "random", BehaviorRandom { it - 1 })
                animator.setMovementEnabled(true); check(movement.queueMovement())
            }
            until { animator.snapshot.moving && animator.currentDirection == PetDirection.RIGHT && animator.snapshot.origin == ActionOrigin.MOVEMENT }
            main { animator.setMovementEnabled(false); animator.onMovement(0, -100) }
            for (action in listOf(PetAction.BLINK, PetAction.WAVE, PetAction.HAPPY, PetAction.LAUGH_FALL, PetAction.BELLY_DRUM)) {
                main { check(animator.play(action)); check(animator.displayedDirection == PetDirection.FRONT) }
                until(8000) { animator.currentAction == PetAction.IDLE }
                main { check(animator.displayedDirection == PetDirection.BACK && root.isAttachedToWindow) }
            }
            main { window.showControls() }
            until { field<View?>(window, "panel")?.isAttachedToWindow == true }
            main {
                val panel = field<View>(window, "panel")
                views(panel).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.size_small) }.performClick()
                check(store.sizeDp == 80 && root.isAttachedToWindow)
                views(panel).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.stop_pet) }.performClick()
                check(!session.isOpen)
            }
            until { !root.isAttachedToWindow }
            check(diagnostics.isEmpty()) { diagnostics.joinToString() }
        } finally { main { session.close(); window.close() } }
    }
}
