package com.naiwa.androidpet.companion

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/** Versioned Java Properties is also available on Android and can be verified on a plain JVM. */
object PetStateCodec {
    fun encode(state: PetState): String {
        val p = Properties()
        fun put(key: String, value: Any) { p.setProperty(key, value.toString()) }
        put("version",1); put("happiness",state.happiness); put("energy",state.energy); put("affinity",state.affinity)
        put("lastInteraction",state.lastInteractionTime); put("count",state.interactionCount)
        put("lastReward",state.lastRewardTime); put("lastRecovery",state.lastRecoveryTime)
        put("actions",state.unlockedActions.sorted().joinToString(","))
        put("achievements",state.achievements.sorted().joinToString(","))
        put("settled",state.settledGameIds.joinToString(","))
        state.bestScores.forEach { (game,score) -> put("best.$game",score) }
        put("recentCount",state.recentInteractions.size)
        state.recentInteractions.forEachIndexed { index,record ->
            put("recent.$index.type",record.type.name); put("recent.$index.at",record.at)
        }
        return StringWriter().also { p.store(it, "Naiwa local memory v1") }.toString()
    }

    fun decode(encoded: String): PetState {
        require(encoded.length <= 65_536)
        val p = Properties().apply { load(StringReader(encoded)) }
        require(p.getProperty("version") == "1") { "不支持的本地记忆版本" }
        fun number(key: String, default: Long): Long = p.getProperty(key)?.toLongOrNull()
            ?: if (p.containsKey(key)) throw IllegalArgumentException("记忆数值损坏：$key") else default
        fun integer(key: String, default: Int): Int = number(key,default.toLong()).also {
            require(it in 0..Int.MAX_VALUE.toLong())
        }.toInt()
        fun list(key: String) = p.getProperty(key, "").split(',').filter(String::isNotEmpty)
        val recentCount = integer("recentCount",0).also { require(it <= 16) }
        val best = PetState.GAME_IDS.mapNotNull { game ->
            if (p.containsKey("best.$game")) game to integer("best.$game",0) else null
        }.toMap()
        return PetState(integer("happiness",72),integer("energy",80),integer("affinity",0),
            number("lastInteraction",0), List(recentCount) {
                InteractionRecord(InteractionType.valueOf(p.getProperty("recent.$it.type")), number("recent.$it.at",0))
            }, list("actions").toSet().ifEmpty { setOf("idle") }, integer("count",0),
            list("achievements").toSet(),best,list("settled"),number("lastReward",0),number("lastRecovery",0))
    }
}
