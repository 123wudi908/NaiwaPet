package com.naiwa.androidpet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.naiwa.androidpet.animation.*
import java.io.File

/** Standalone ART entry: compiled to a DEX jar, never packaged as an APK. */
object Stage2ArtProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            val names = listOf("animation.WalkCompletionTest", "animation.AnimationControllerTest",
                "animation.SpriteSizingTest", "animation.DecodeSizingTest", "behavior.AutoMoveTest",
                "behavior.ActionPriorityTest", "behavior.PetInteractionTest", "animation.DirectionalAnimationTest")
            val types = names.map { Class.forName("com.naiwa.androidpet.$it") }.toTypedArray()
            val core = Class.forName("org.junit.runner.JUnitCore")
            val result = checkNotNull(core.getMethod("runClasses", types.javaClass).invoke(null, types as Any))
            val successful = result.javaClass.getMethod("wasSuccessful").invoke(result) as Boolean
            val count = result.javaClass.getMethod("getRunCount").invoke(result)
            check(successful) { "JUnit failures: ${result.javaClass.getMethod("getFailures").invoke(result)}" }
            println("PASS ART JUnit: $count relevant tests; newly compiled production controller")
            verifyPixels(File(args.single()))
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            realScheduler()
            Looper.loop()
        } catch (error: Throwable) { error.printStackTrace(); kotlin.system.exitProcess(1) }
    }

    private fun verifyPixels(root: File) {
        val original = File(root, "originals/naiwa")
        val admitted = setOf("idle", "blink", "wave", "happy", "walk", "think", "laugh", "dance",
            "fake_fall", "laugh_fall", "loading_brain", "freeze", "wobble", "belly_drum")
        val pngs = original.walkTopDown().filter { it.isFile && it.extension == "png" &&
            it.parentFile?.name in admitted && it.parentFile?.parentFile == original }.toList()
        check(pngs.size == 86) { "Expected 86 original PNGs, got ${pngs.size}" }
        val candidates = File(root, "candidates").walkTopDown().filter { it.isFile && it.extension == "png" }.toList()
        check(candidates.size >= 5) { "Missing isolated recovery poses" }
        for (file in pngs + candidates) {
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { file.path }
            check(bitmap.width == 640 && bitmap.height == 640 && bitmap.hasAlpha()) { file.path }
            val rendered = Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
            val x = if (file.parentFile?.name == "walk" && file in pngs) 38 else 0
            Canvas(rendered).drawBitmap(bitmap,Rect(0,0,640,640),Rect(x,0,640+x,640),null)
            val pixels = IntArray(640*640); rendered.getPixels(pixels,0,640,0,0,640,640)
            check(pixels.count { it ushr 24 >= 220 } > 1000) { "Empty native sprite: $file" }
            rendered.recycle(); bitmap.recycle()
        }
        println("PASS Android BitmapFactory / Canvas: 86 originals + ${candidates.size} isolated candidates, 640x640 RGBA")
    }

    private fun realScheduler() {
        val handler = Handler(Looper.getMainLooper())
        val scheduler = AndroidFrameScheduler()
        val presented = mutableListOf<Pair<Long,String>>()
        val base = SystemClock.uptimeMillis()
        var stoppedAt = 0L
        var frameAtStop: Pair<Long,String>? = null
        var countAtStop = 0
        lateinit var player: AnimationController
        val sink = object : FrameSink {
            override fun present(path: String, completion: (Result<Unit>) -> Unit) {
                presented += SystemClock.uptimeMillis()-base to path
                completion(Result.success(Unit))
                if(path=="idle" && stoppedAt>0) handler.post {
                    try {
                        val lastWalk = presented.last { it.second.startsWith("walk/") }
                        val idle = presented.last { it.second=="idle" }
                        check(lastWalk==frameAtStop) { "Stop started a further WALK frame" }
                        check(idle.first-lastWalk.first>=125) { "Visible final WALK frame was truncated" }
                        check(idle.first-stoppedAt<=400) { "Stop ran an extra cycle" }
                        check(presented.count { it.second.startsWith("walk/") }==countAtStop)
                        println("PASS Android Handler/Looper: requested stop=${stoppedAt}ms, last WALK=${lastWalk.first}ms, IDLE=${idle.first}ms")
                        player.close(); pendingDecodeCase(handler)
                    } catch(error:Throwable){error.printStackTrace();kotlin.system.exitProcess(1)}
                }
            }
            override fun clear() = Unit
            override fun close() = Unit
        }
        player = AnimationController(clips(),scheduler,sink){error(it)}
        player.start(); check(player.request(PetAction.WALK,ActionOrigin.GAME))
        handler.postDelayed({
            stoppedAt=SystemClock.uptimeMillis()-base
            frameAtStop=presented.last { it.second.startsWith("walk/") }
            countAtStop=presented.count { it.second.startsWith("walk/") }
            player.finishPreviewWalk()
        },1400)
        handler.postDelayed({error("ART walk completion timed out")},4000)
    }

    private fun pendingDecodeCase(handler: Handler) {
        var generation=0;var visible="";var count=0
        val sink=object:FrameSink {
            override fun present(path:String,completion:(Result<Unit>)->Unit){
                val ticket=++generation
                val deliver={
                    if(ticket==generation){visible=path;if(path.startsWith("walk/"))count++}
                    completion(Result.success(Unit))
                }
                if(path=="walk/2")handler.postDelayed(deliver,250) else deliver()
            }
            override fun cancelPending(){generation++}
            override fun clear()=Unit
            override fun close()=Unit
        }
        val player=AnimationController(clips(),AndroidFrameScheduler(),sink){error(it)}
        player.start();player.request(PetAction.WALK,ActionOrigin.MOVEMENT)
        handler.postDelayed({player.finishMovement()},170)
        handler.postDelayed({
            try{
                check(player.currentAction==PetAction.IDLE && visible=="idle" && count==1)
                player.close();println("PASS Android delayed decode: stop does not await or resurrect an unshown frame")
                println("STAGE2_ART_SUCCESS");kotlin.system.exitProcess(0)
            }catch(error:Throwable){error.printStackTrace();kotlin.system.exitProcess(1)}
        },700)
    }

    private fun clips()=mapOf(
        PetAction.IDLE to AnimationClip(PetAction.IDLE,listOf("idle"),2,true),
        PetAction.WALK to AnimationClip(PetAction.WALK,(1..6).map{"walk/$it"},8,true,frameDurationsMillis=List(6){125}),
    )
}
