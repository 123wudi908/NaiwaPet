package com.naiwa.androidpet.data

import android.content.Context
import com.naiwa.androidpet.companion.*
import com.naiwa.androidpet.runtime.PetDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One application-scoped store shared by the Activity, games and overlay. No account or network. */
class PetStateRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("naiwa_memory",Context.MODE_PRIVATE)
    private val rejectedFile = File(context.applicationContext.filesDir,"memory-rejected.txt")
    private val settings = PetPreferences(context.applicationContext)
    private val listeners = linkedSetOf<() -> Unit>()
    private var state = load()

    private fun load(): PetState {
        val encoded = prefs.getString("state_v1",null) ?: return PetState()
        return try { PetStateCodec.decode(encoded) } catch (error: Exception) {
            try { rejectedFile.writeText(encoded.take(65_536)) } catch (_: Exception) { }
            PetDiagnostics.event("memory.invalid", error = error)
            settings.lastDiagnostic = "本地记忆无法读取，原记录已保留；当前使用初始状态。"
            PetState()
        }
    }

    @Synchronized fun read(): PetState { update(state.recover(System.currentTimeMillis())); return state }
    @Synchronized fun interact(type: InteractionType): PetState {
        update(state.interact(type,System.currentTimeMillis())); return state
    }
    @Synchronized fun discover(action: String) { update(state.discover(action)) }
    @Synchronized fun settleGame(id: String,game: String,score: Int): PetState {
        update(state.settleGame(id,game,score,System.currentTimeMillis())); return state
    }
    @Synchronized fun reset() { update(PetState(lastRecoveryTime=System.currentTimeMillis())) }
    @Synchronized fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    @Synchronized fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }

    private fun update(next: PetState) {
        if (state == next) return
        state = next
        prefs.edit().putString("state_v1",PetStateCodec.encode(next)).apply()
        listeners.toList().forEach { it() }
    }

    @Synchronized fun exportJson(): String {
        val s = read()
        return JSONObject().apply {
            put("schema",1); put("happiness",s.happiness); put("energy",s.energy); put("affinity",s.affinity)
            put("lastInteractionTime",s.lastInteractionTime); put("interactionCount",s.interactionCount)
            put("unlockedActions",JSONArray(s.unlockedActions.sorted())); put("achievements",JSONArray(s.achievements.sorted()))
            put("bestScores",JSONObject(s.bestScores)); put("recentInteractions",JSONArray().apply {
                s.recentInteractions.forEach { put(JSONObject().put("type",it.type.name).put("at",it.at)) }
            })
        }.toString(2)
    }

    companion object {
        @Volatile private var instance: PetStateRepository? = null
        fun get(context: Context): PetStateRepository = instance ?: synchronized(this) {
            instance ?: PetStateRepository(context).also { instance = it }
        }
    }
}
