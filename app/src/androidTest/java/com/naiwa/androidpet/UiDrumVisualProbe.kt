package com.naiwa.androidpet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.behavior.HorizontalMoveHost
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.game.*
import com.naiwa.androidpet.overlay.PetFrameView
import com.naiwa.androidpet.ui.*
import java.io.File
import java.lang.reflect.Proxy

/** Offscreen native Views + real PNG decoder/clock, executed as a DEX jar, never an APK. */
object UiDrumVisualProbe {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var output: File
    private fun field(owner:Any,name:String):Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
    private fun set(owner:Any,name:String,value:Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(owner,value)
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun draw(view:View,name:String,w:Int=1080,h:Int=1640):Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { bitmap ->
            view.draw(Canvas(bitmap))
            File(output,"$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        }
    }
    private fun later(delay:Long=0,block:()->Unit) { handler.postDelayed({
        try { block() } catch(t:Throwable) { t.printStackTrace();kotlin.system.exitProcess(1) }
    },delay) }

    @JvmStatic fun main(args:Array<String>) {
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            output=File(args.single()).also { it.mkdirs() }
            val thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val system=thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            val original=system.createPackageContext("com.naiwa.androidpet",Context.CONTEXT_IGNORE_SECURITY)
            val context=object:ContextWrapper(original) {
                override fun getApplicationContext():Context=this
                override fun getSharedPreferences(name:String,mode:Int)=original.getSharedPreferences("ui_drum_probe_$name",mode)
            }
            later { checkScreens(context) }
            Looper.loop()
        } catch(t:Throwable) { t.printStackTrace();kotlin.system.exitProcess(1) }
    }

    private class Screen(context:Context):Activity() {
        lateinit var content:View
        private val manager=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val offscreenManager=Proxy.newProxyInstance(WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)) { _,method,args ->
            when(method.name) {
                "addView","removeView","removeViewImmediate","updateViewLayout" -> null
                else -> method.invoke(manager,*(args ?: emptyArray()))
            }
        }
        init {
            attachBaseContext(context)
            setTheme(resources.getIdentifier("AppTheme","style",packageName))
            val window=Class.forName("com.android.internal.policy.PhoneWindow").getConstructor(Context::class.java).newInstance(this)
            Activity::class.java.getDeclaredField("mWindow").apply { isAccessible=true }.set(this,window)
        }
        override fun getSystemService(name:String):Any? = if(name==Context.WINDOW_SERVICE)offscreenManager else baseContext.getSystemService(name)
        override fun setContentView(view:View) { content=view }
    }

    private fun checkScreens(context:Context) {
        val screen=Screen(context)
        val ui=CompanionUi(screen,PetPreferences(context),{},{},{},{},{},{},{},{},{ _,_ -> })
        ui.setActive(true)
        val home=field(ui,"preview") as PetPreview
        (field(home,"frame") as PetFrameView).setFrame(context.assets.open("naiwa/idle/frame01.png").use { BitmapFactory.decodeStream(it)!! })
        draw(screen.content,"home")
        check(all(screen.content).count { it.contentDescription=="动作图鉴" }==1)
        all(screen.content).single { it.contentDescription=="动作图鉴" }.performClick()
        draw(screen.content,"gallery")
        val catalog=PetMaterials.load(context)
        val available=PetAction.entries.filter(catalog.pool::canManualFormal)
        check(available.size==14)
        val rows=all(screen.content).filter { it.contentDescription?.startsWith("播放")==true }
        check(rows.size==14)
        check(all(screen.content).filterIsInstance<TextView>().none { "还没收录" in it.text || "暂未收录" in it.text })
        val scroll=field(ui,"scroll") as android.widget.ScrollView
        scroll.scrollTo(0,480)
        val before=scroll.scrollY
        rows.first { "肚皮" in it.contentDescription }.performClick()
        val dialog=field(ui,"actionDialog") as android.app.AlertDialog
        check(dialog.isShowing)
        draw(dialog.window!!.decorView,"gallery-player",900,1200)
        dialog.dismiss();check(scroll.scrollY==before)
        ui.show(AppPage.SETTINGS);draw(screen.content,"settings")
        check(all(screen.content).filterIsInstance<TextView>().any { it.text.toString()=="当前版本 v0.7.0" })
        check(all(screen.content).filterIsInstance<TextView>().none { "-personal" in it.text })
        ui.close()
        println("PASS native HOME book entry, 14 real thumbnails, playback dialog, preserved scroll, runtime version label")
        checkDrum(context)
    }

    private fun checkDrum(context:Context) {
        val game=GameSession(GameKind.DRUM,"native-ui-drum",1)
        val board=GameBoardView(context,game){}
        val pet=field(board,"pet") as PetPreview
        val frame=field(pet,"frame") as PetFrameView
        draw(board,"drum-ready",900,900)
        val host=object:HorizontalMoveHost {
            override var position=Position(0,0)
            override val size get()=PetSize(pet.width,pet.height)
            override val area get()=ScreenArea(0,0,pet.width,pet.height)
            override fun moveTo(position:Position){this.position=position}
            override fun savePosition(){}
        }
        val animator=PetAnimator(context,frame,host,{error(it)},{},false)
        animator.setAutomaticEnabled(false);animator.setMovementEnabled(false)
        set(pet,"animator",animator);animator.resize(PetSize(pet.width,pet.height));pet.setActive(true)
        check(board.getChildAt(board.childCount-1)===field(board,"field"))
        game.start();game.advance(1400)
        later(180) {
            val bitmap=draw(board,"drum-before-hit",900,900)
            val cx=board.width/2;val cy=(board.height-pet.width*.43f-NativeStyle.dp(context,4)).toInt()
            val radius=NativeStyle.dp(context,24)
            check(kotlin.math.abs(android.graphics.Color.green(bitmap.getPixel(cx+radius,cy))-108)<12) { "Ring hidden by opaque PNG" }
            check(game.tap()==HitFeedback.PERFECT)
            board.javaClass.getDeclaredMethod("hit",HitFeedback::class.java).apply { isAccessible=true }.invoke(board,HitFeedback.PERFECT)
            check(animator.currentAction==PetAction.BELLY_DRUM)
            later(120) {
                draw(board,"drum-during-hit",900,900)
                check(pet.scaleX==1f && pet.scaleY==1f)
                later(650) {
                    check(animator.currentAction==PetAction.IDLE)
                    game.advance(game.remainingMillis);board.sync()
                    check(animator.currentAction==PetAction.IDLE)
                    draw(board,"drum-finished",900,900)
                    board.close()
                    println("PASS native ring above PNG, hit BELLY_DRUM, stable scale, one-shot returns IDLE, no end HAPPY")
                    println("UI_DRUM_NATIVE_SUCCESS");kotlin.system.exitProcess(0)
                }
            }
        }
    }
}
