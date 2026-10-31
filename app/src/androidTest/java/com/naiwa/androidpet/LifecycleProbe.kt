package com.naiwa.androidpet

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.overlay.*

/** Native fault injection using framework APIs only. Packaged exclusively in the test APK. */
class LifecycleProbe : Instrumentation() {
    private val failures=mutableListOf<String>()
    private var passed=0
    private var updateOnly=false
    private var v070Only=false
    private var frontAudit=false
    private var dragHomeOnly=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);updateOnly=arguments?.getString("updateOnly")=="true";v070Only=arguments?.getString("v070Only")=="true";frontAudit=arguments?.getString("frontAudit")=="true";dragHomeOnly=arguments?.getString("dragHomeOnly")=="true";start()}
    override fun onStart(){
        if(dragHomeOnly){DragHomeSmoke.run(this);return}
        if(updateOnly){UpdateProbe(this).run();return}
        if(v070Only){V070Probe(this).run();return}
        if(frontAudit){FrontActionAuditProbe(this).run();return}
        try{
            com.naiwa.androidpet.runtime.PetDiagnostics.initialize(targetContext)
            check(Settings.canDrawOverlays(targetContext)){"Grant the isolated test app overlay permission first"}
            runCase("real PNG failure preserves visible pixels"){decodeFailure()}
            runCase("window action panel and bounded detach recovery"){windowLifecycle()}
            runCase("game character measures fully and finished board releases scrolling"){gameLayout()}
            runCase("every approved PNG decodes and draws on Android"){allFrames()}
            runCase("platform rejects app spoofing of screen broadcasts"){protectedScreenEvents()}
        }catch(error:Throwable){failures.add(error.stackTraceToString())}
        finish(if(failures.isEmpty())Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{
            putInt("passed",passed);putInt("failed",failures.size)
            putString("stream","\nNaiwa native probes: $passed passed, ${failures.size} failed\n"+failures.joinToString("\n"))
        })
    }
    private fun runCase(name:String,block:()->Unit){
        try{block();passed++;sendStatus(0,Bundle().apply{putString("stream","PASS $name\n")})}
        catch(error:Throwable){failures.add("$name\n${error.stackTraceToString()}");sendStatus(0,Bundle().apply{putString("stream","FAIL $name\n")})}
    }
    private fun main(block:()->Unit){
        var failure:Throwable?=null
        runOnMainSync{try{block()}catch(error:Throwable){failure=error}}
        failure?.let{throw it}
    }
    private fun until(timeout:Long=6000,condition:()->Boolean){
        val deadline=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<deadline){var okay=false;main{okay=condition()};if(okay)return;SystemClock.sleep(40)}
        error("Native condition timed out after ${timeout}ms")
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner:Any,name:String):T=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner) as T

    private fun decodeFailure(){
        lateinit var view:PetFrameView
        lateinit var controller:AnimationController
        val errors=mutableListOf<String>()
        main{
            val catalog=AssetAnimationCatalog.load(targetContext.assets)
            view=PetFrameView(targetContext);view.layout(0,0,256,256)
            val clips=mapOf(PetAction.IDLE to AnimationClip(PetAction.IDLE,
                listOf("naiwa/idle/frame01.png","naiwa/idle/not-a-real-frame.png"),5,true))
            controller=AnimationController(clips,AndroidFrameScheduler(),PngFrameSink(targetContext.assets,catalog.layout,view),errors::add)
            controller.start()
        }
        try{
            until{field<Bitmap?>(view,"frame")!=null}
            until{errors.isNotEmpty()}
            SystemClock.sleep(350)
            main{
                val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels=IntArray(256*256);bitmap.getPixels(pixels,0,256,0,0,256,256)
                check(pixels.count{it ushr 24>0}>1000){"The window renderer erased every pet pixel after one failed frame"}
                check(!controller.isPaused){"A single bad idle frame permanently paused the player"}
                check(errors.size==1){"Bad frame was retried without a bound"}
                bitmap.recycle()
            }
        }finally{main{controller.close()}}
    }

    private fun windowLifecycle(){
        lateinit var controller:OverlayWindowController
        lateinit var session:OverlaySession
        lateinit var root:View
        val diagnostics=mutableListOf<String>()
        val store=object:OverlayStore{override var position:Position?=Position(60,360);override var sizeDp=120}
        main{
            controller=OverlayWindowController(targetContext,{session.position},{session.moveTo(it)},
                {session.savePosition()},{session.close()},{store.sizeDp=it},{session.updateArea(it)},
                diagnostics::add,{throw it})
            session=OverlaySession(controller,store)
            check(session.start(controller.screenArea(),PetSize(360,360),true))
            controller.setVisible(true)
            root=field(controller,"root")
            check(session.start(controller.screenArea(),PetSize(360,360),true))
            check(field<View>(controller,"root")===root){"Repeated start replaced the window"}
        }
        try{
            until{root.isAttachedToWindow && root.visibility==View.VISIBLE}
            val actions=mutableListOf<PetAction>()
            main{val animator=field<PetAnimator>(controller,"animator");actions.addAll(PetAction.entries.filter(animator::canManual))}
            check(actions.size==14){"Expected all 14 admitted actions"}
            for(action in actions){
                main{check(field<PetAnimator>(controller,"animator").play(action)){"Manual action rejected: $action"}}
                until(15_000){field<PetAnimator>(controller,"animator").currentAction==PetAction.IDLE}
                main{check(root.isAttachedToWindow && root.visibility==View.VISIBLE){"Window lost after $action"}}
            }
            com.naiwa.androidpet.runtime.PetDiagnostics.event("probe.actions_checked","count=${actions.size}")
            main{check(root.isAttachedToWindow);controller.showControls()}
            until{field<View?>(controller,"panel")?.isAttachedToWindow==true}
            main{
                controller.javaClass.getDeclaredMethod("closePanel").apply{isAccessible=true}.invoke(controller)
                check(root.isAttachedToWindow){"Collapsing controls removed the pet"}
                check(field<View?>(controller,"panel")==null)
                session.moveTo(Position(120,480));session.resize(PetSize(300,300),100)
            }
            val savedPosition=store.position
            repeat(2){
                main{field<WindowManager>(controller,"manager").removeViewImmediate(root)}
                until{root.isAttachedToWindow && root.visibility==View.VISIBLE}
                main{check(field<View>(controller,"root")===root);check(store.position==savedPosition)}
            }
            main{field<WindowManager>(controller,"manager").removeViewImmediate(root)}
            SystemClock.sleep(1400)
            main{
                check(!root.isAttachedToWindow){"Recovery retries exceeded their lifetime budget"}
                check(diagnostics.any{it.contains("恢复次数")}){"Exhausted recovery has no useful diagnostic"}
                session.close()
            }
            SystemClock.sleep(700)
            main{check(!root.isAttachedToWindow);check(!session.isOpen)}
        }finally{
            main{try{session.close()}catch(_:RuntimeException){};try{controller.close()}catch(_:RuntimeException){}}
        }
    }

    private fun gameLayout(){
        main{
            val game=com.naiwa.androidpet.game.GameSession(com.naiwa.androidpet.game.GameKind.COMBO)
            val board=com.naiwa.androidpet.game.GameBoardView(targetContext,game,{})
            try{
                board.measure(View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY))
                board.layout(0,0,936,780)
                val character=board.getChildAt(1)
                check(character.measuredWidth>=500 && character.measuredHeight>=500){
                    "Game character measured ${character.measuredWidth}x${character.measuredHeight}, not a visible full character"}
                val event=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,100f,100f,0)
                try{check(!board.onTouchEvent(event)){"A non-running game swallowed its parent scroll gesture"}}
                finally{event.recycle()}
            }finally{board.close()}
        }
    }

    private fun allFrames(){
        lateinit var view:PetFrameView
        lateinit var sink:PngFrameSink
        lateinit var catalog:AssetAnimationCatalog
        main{
            catalog=AssetAnimationCatalog.load(targetContext.assets)
            check(catalog.diagnostics.isEmpty()){catalog.diagnostics.joinToString()}
            view=PetFrameView(targetContext);view.layout(0,0,256,256)
            sink=PngFrameSink(targetContext.assets,catalog.layout,view,catalog.frameLayouts);sink.resize(256)
        }
        var count=0
        try{
            for(clip in catalog.clips.values)for(path in clip.frames){
                var complete=false
                var failure:Throwable?=null
                main{sink.present(path){failure=it.exceptionOrNull();complete=true}}
                until{complete};check(failure==null){"$path: $failure"}
                main{
                    val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val pixels=IntArray(256*256);bitmap.getPixels(pixels,0,256,0,0,256,256)
                    check(pixels.count{it ushr 24>0}>1000){"Invisible Android frame: $path"}
                    bitmap.recycle()
                }
                count++
            }
            check(count==86){"Expected all 86 admitted frames, checked $count"}
        }finally{main{sink.close()}}
    }

    private fun protectedScreenEvents(){
        for(action in listOf(android.content.Intent.ACTION_SCREEN_OFF,android.content.Intent.ACTION_SCREEN_ON,
            android.content.Intent.ACTION_USER_PRESENT)){
            var rejected=false
            try{targetContext.sendBroadcast(android.content.Intent(action))}catch(_:SecurityException){rejected=true}
            check(rejected){"Platform unexpectedly allowed an app to spoof $action"}
        }
    }
}
