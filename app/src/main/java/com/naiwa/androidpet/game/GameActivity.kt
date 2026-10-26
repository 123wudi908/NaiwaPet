package com.naiwa.androidpet.game

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.naiwa.androidpet.data.PetStateRepository
import com.naiwa.androidpet.runtime.PetRuntime
import com.naiwa.androidpet.ui.NativeStyle
import com.naiwa.androidpet.R

class GameActivity : Activity() {
    private lateinit var session: GameSession
    private lateinit var board: GameBoardView
    private lateinit var score: TextView
    private lateinit var state: TextView
    private lateinit var result: TextView
    private lateinit var mainButton: Button
    private lateinit var pauseButton: Button
    private lateinit var intro: LinearLayout
    private lateinit var pageScroll: ScrollView
    private var landscape=false
    private var settledId: String? = null
    private var backCallback: OnBackInvokedCallback? = null
    private val screenKey="game-${System.identityHashCode(this)}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val snapshot = if (Build.VERSION.SDK_INT >= 33) savedInstanceState?.getSerializable("game",GameSnapshot::class.java)
            else savedInstanceState?.getSerializable("game") as? GameSnapshot
        session = try { snapshot?.let(GameSession::restore) ?: GameSession(GameKind.fromId(intent.getStringExtra("kind"))) }
            catch (_: RuntimeException) { GameSession(GameKind.fromId(intent.getStringExtra("kind"))) }
        buildPage()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback=OnBackInvokedCallback { exitGame() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,checkNotNull(backCallback))
        }
    }
    private fun buildPage() {
        landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NativeStyle.background) }
        val toolbar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setPadding(dp(20),dp(8),dp(20),dp(8)) }
        toolbar.addView(NativeStyle.button(this,"返回") { exitGame() },LinearLayout.LayoutParams(0,-2,1f))
        pauseButton = NativeStyle.button(this,"暂停") { session.pause(); board.sync(); renderState() }
        toolbar.addView(pauseButton,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) })
        root.addView(toolbar)
        val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(24)) }
        intro=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        intro.addView(NativeStyle.text(this,session.kind.title,if(landscape)22f else 28f,true))
        intro.addView(NativeStyle.text(this,session.kind.description,if(landscape)12f else 14f))
        body.addView(intro)
        score = NativeStyle.text(this,"",if(resources.configuration.fontScale>=1.5f)16f else 22f,true)
        state = NativeStyle.text(this,"",14f)
        if(landscape){body.addView(score);body.addView(state)}else{
            val summary=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
            summary.addView(score);summary.addView(state);root.addView(summary)
        }
        board = GameBoardView(this,session,::renderState)
        val boardHeight = minOf(dp(350),(resources.displayMetrics.heightPixels*.44).toInt()).coerceAtLeast(dp(220))
        if(!landscape)body.addView(board,LinearLayout.LayoutParams(-1,boardHeight).apply { topMargin=dp(12);bottomMargin=dp(12) })
        result = NativeStyle.text(this,"",15f,true).also { body.addView(it) }
        mainButton = NativeStyle.button(this,"开始游戏",true) {
            when (session.phase) {
                GamePhase.READY -> session.start()
                GamePhase.PAUSED -> session.resume()
                GamePhase.FINISHED -> {
                    session=GameSession(session.kind);board.session=session;settledId=null;session.start()
                }
                GamePhase.RUNNING -> Unit
            }
            board.sync();renderState()
            if(session.phase==GamePhase.RUNNING)board.post{
                if(landscape)pageScroll.scrollTo(0,0)
                else board.requestRectangleOnScreen(android.graphics.Rect(0,0,board.width,board.height),true)
            }
        }.also { body.addView(it) }
        body.addView(NativeStyle.button(this,"回到奶蛙之家") {
            if(session.phase==GamePhase.FINISHED || session.phase==GamePhase.READY){
                startActivity(Intent(this,com.naiwa.androidpet.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page","HOME"))
                finish()
            }else exitGame()
        })
        pageScroll = ScrollView(this).apply { isFillViewport=true;addView(body) }
        if(landscape){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(4),dp(20),dp(12))}
            row.addView(pageScroll,LinearLayout.LayoutParams(0,-1,1f))
            row.addView(board,LinearLayout.LayoutParams(0,-1,1.1f))
            root.addView(row,LinearLayout.LayoutParams(-1,0,1f))
        }else root.addView(pageScroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);NativeStyle.installInsets(this,root);renderState()
    }
    private fun renderState() {
        if (!::score.isInitialized) return
        score.text = getString(R.string.game_score_combo,session.score,session.combo)
        val seconds = (session.remainingMillis+999)/1000
        state.text = when (session.phase) {
            GamePhase.READY -> "准备开始"
            GamePhase.RUNNING -> "${seconds} 秒 · 第 ${session.stage} 阶段"
            GamePhase.PAUSED -> "已暂停"
            GamePhase.FINISHED -> "这一局结束啦"
        }
        pauseButton.isEnabled = session.phase == GamePhase.RUNNING
        intro.visibility=if(landscape && session.phase!=GamePhase.READY)View.GONE else View.VISIBLE
        mainButton.visibility = if (session.phase == GamePhase.RUNNING) View.GONE else View.VISIBLE
        mainButton.text = when (session.phase) { GamePhase.PAUSED -> "继续游戏"; GamePhase.FINISHED -> "再玩一次"; else -> "开始游戏" }
        if (session.phase == GamePhase.FINISHED) {
            val memory = PetStateRepository.get(this)
            if (settledId != session.id) {
                memory.settleGame(session.id,session.kind.id,session.score);settledId=session.id
                PetRuntime.completedGame("刚才一起玩了 ${session.score} 分，还想再来吗？")
            }
            val best = memory.read().bestScores[session.kind.id] ?: 0
            result.text = getString(R.string.game_settlement,session.score,best,session.maxCombo)
        } else result.text = if (session.phase == GamePhase.RUNNING) board.feedbackText else "在应用里玩，切到后台会自动暂停。"
    }
    private fun exitGame() {
        if (session.phase == GamePhase.RUNNING) { session.pause();board.sync();renderState() }
        if (session.phase == GamePhase.PAUSED) {
            AlertDialog.Builder(this).setTitle("退出这一局？").setMessage("本局尚未结束，不计入成绩。你也可以留在这里继续玩。")
                .setNegativeButton("留下") { _,_ -> }.setPositiveButton("退出本局") { _,_ -> finish() }.show()
        } else finish()
    }
    override fun onStart() { super.onStart();PetRuntime.screenVisible(screenKey,true) }
    override fun onResume() { super.onResume();board.setActive(true);renderState() }
    override fun onPause() { session.pause();board.setActive(false);renderState();super.onPause() }
    override fun onStop() { PetRuntime.screenVisible(screenKey,false);super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable("game",session.snapshot());super.onSaveInstanceState(outState)
    }
    @Deprecated("Legacy Android back dispatch")
    @SuppressLint("GestureBackNavigation") // API33+ registers the platform callback above; this is the API26–32 fallback.
    override fun onBackPressed() { exitGame() }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        board.close();super.onDestroy()
    }
    private fun dp(value:Int)=NativeStyle.dp(this,value)
    companion object {
        fun intent(context: Context,kind:GameKind)=Intent(context,GameActivity::class.java).putExtra("kind",kind.id)
    }
}
