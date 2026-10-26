package com.naiwa.androidpet.game

import java.io.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class GameKind(val id: String, val title: String, val description: String, val durationMillis: Long) {
    DRUM("drum","肚皮打鼓","等圆环靠近肚皮，跟着节拍轻点。",24_000),
    COMBO("combo","连击发疯挑战","十五秒内连续轻点，连击越长分数越高。",15_000),
    BUBBLES("bubbles","接住奶泡","左右滑动奶蛙，接住落下来的奶泡。",30_000);
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DRUM }
}
enum class GamePhase { READY, RUNNING, PAUSED, FINISHED }
enum class HitFeedback { IGNORED, MISS, GOOD, PERFECT }
data class Bubble(val id: Int, val x: Float, val bornAt: Long) : Serializable
data class GameSnapshot(val kind: GameKind, val id: String, val phase: GamePhase, val elapsed: Long,
    val score: Int, val combo: Int, val maxCombo: Int, val misses: Int, val playerX: Float,
    val lastTap: Long, val nextSpawn: Long, val randomState: Long, val bubbleId: Int,
    val judged: Set<Int>, val bubbles: List<Bubble>) : Serializable

/** One clock for each game. Android provides elapsed foreground time; pauses never accumulate time. */
class GameSession(val kind: GameKind, val id: String = UUID.randomUUID().toString(), seed: Long = System.nanoTime()) {
    var phase = GamePhase.READY; private set
    var elapsedMillis = 0L; private set
    var score = 0; private set
    var combo = 0; private set
    var maxCombo = 0; private set
    var misses = 0; private set
    var playerX = .5f; private set
    private var lastTap = -1L
    private var nextSpawn = 0L
    private var randomState = ((seed and Long.MAX_VALUE) % 2_147_483_646L)+1
    private var bubbleId = 0
    private val judged = mutableSetOf<Int>()
    private val falling = mutableListOf<Bubble>()
    val bubbles: List<Bubble> get() = falling.toList()
    val remainingMillis: Long get() = (kind.durationMillis-elapsedMillis).coerceAtLeast(0)
    val stage: Int get() = if (maxCombo >= 30) 3 else if (maxCombo >= 10) 2 else 1

    fun start() { if (phase == GamePhase.READY) phase = GamePhase.RUNNING }
    fun pause() { if (phase == GamePhase.RUNNING) phase = GamePhase.PAUSED }
    fun resume() { if (phase == GamePhase.PAUSED) phase = GamePhase.RUNNING }

    fun advance(deltaMillis: Long) {
        require(deltaMillis >= 0)
        if (phase != GamePhase.RUNNING) return
        elapsedMillis += minOf(deltaMillis,remainingMillis)
        when (kind) {
            GameKind.DRUM -> {
                var beat = 0
                while (1000+beat*600L < kind.durationMillis) {
                    if (elapsedMillis > 1180+beat*600L && judged.add(beat)) { combo = 0; misses++ }
                    beat++
                }
            }
            GameKind.BUBBLES -> {
                while (nextSpawn <= elapsedMillis && nextSpawn < kind.durationMillis-2200) {
                    randomState = randomState*48_271 % 2_147_483_647
                    falling.add(Bubble(bubbleId++,.1f+(randomState%801).toFloat()/1000,nextSpawn))
                    nextSpawn += 700
                }
                val landed = falling.filter { elapsedMillis-it.bornAt >= 2158 }
                landed.forEach {
                    if (abs(it.x-playerX) <= .11f) { score += 10; combo++; maxCombo = maxOf(maxCombo,combo) }
                    else { combo = 0; misses++ }
                }
                falling.removeAll(landed.toSet())
            }
            GameKind.COMBO -> if (lastTap >= 0 && elapsedMillis-lastTap > 900) combo = 0
        }
        if (remainingMillis == 0L) phase = GamePhase.FINISHED
    }

    fun tap(): HitFeedback {
        if (phase != GamePhase.RUNNING || kind == GameKind.BUBBLES) return HitFeedback.IGNORED
        if (lastTap >= 0 && elapsedMillis-lastTap < 60) return HitFeedback.IGNORED
        if (kind == GameKind.COMBO) {
            combo = if (lastTap >= 0 && elapsedMillis-lastTap <= 900) combo+1 else 1
            lastTap = elapsedMillis; maxCombo = maxOf(maxCombo,combo)
            score += 10+combo.coerceAtMost(20)
            return HitFeedback.PERFECT
        }
        val beat = ((elapsedMillis-1000)/600.0).roundToInt().coerceAtLeast(0)
        if (beat in judged) return HitFeedback.IGNORED
        val error = abs(elapsedMillis-(1000+beat*600L))
        lastTap = elapsedMillis
        return when {
            error <= 80 -> { judged.add(beat); score += 100; combo++; maxCombo = maxOf(maxCombo,combo); HitFeedback.PERFECT }
            error <= 180 -> { judged.add(beat); score += 60; combo++; maxCombo = maxOf(maxCombo,combo); HitFeedback.GOOD }
            else -> { combo = 0; misses++; HitFeedback.MISS }
        }
    }

    fun moveTo(x: Float) {
        if (phase == GamePhase.RUNNING && kind == GameKind.BUBBLES && x.isFinite()) playerX = x.coerceIn(.08f,.92f)
    }
    fun snapshot() = GameSnapshot(kind,id,phase,elapsedMillis,score,combo,maxCombo,misses,playerX,
        lastTap,nextSpawn,randomState,bubbleId,judged.toSet(),falling.toList())

    companion object {
        fun restore(s: GameSnapshot): GameSession {
            require(s.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && s.elapsed in 0..s.kind.durationMillis &&
                s.score in 0..1_000_000 && s.bubbles.size <= 8 && s.playerX.isFinite() &&
                s.judged.size <= 64 && s.nextSpawn in 0..31_000)
            return GameSession(s.kind,s.id,s.randomState).apply {
                phase = if (s.phase == GamePhase.RUNNING) GamePhase.PAUSED else s.phase
                elapsedMillis = s.elapsed; score = s.score; combo = s.combo; maxCombo = s.maxCombo
                misses = s.misses; playerX = s.playerX; lastTap = s.lastTap; nextSpawn = s.nextSpawn
                randomState = s.randomState; bubbleId = s.bubbleId
                judged.addAll(s.judged); falling.addAll(s.bubbles)
            }
        }
    }
}
