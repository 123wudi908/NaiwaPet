package com.naiwa.androidpet.companion

enum class InteractionType { TAP, COMBO, SWIPE, DRAG, GAME }
data class InteractionRecord(val type: InteractionType, val at: Long)

/** Local, gentle progression. Time away only restores energy; friendship never decays. */
data class PetState(
    val happiness: Int = 72,
    val energy: Int = 80,
    val affinity: Int = 0,
    val lastInteractionTime: Long = 0,
    val recentInteractions: List<InteractionRecord> = emptyList(),
    val unlockedActions: Set<String> = setOf("idle"),
    val interactionCount: Int = 0,
    val achievements: Set<String> = emptySet(),
    val bestScores: Map<String, Int> = emptyMap(),
    val settledGameIds: List<String> = emptyList(),
    val lastRewardTime: Long = 0,
    val lastRecoveryTime: Long = 0,
) {
    init {
        require(happiness in 0..100 && energy in 0..100 && affinity in 0..100)
        require(interactionCount >= 0 && lastInteractionTime >= 0 && lastRewardTime >= 0 && lastRecoveryTime >= 0)
        require(recentInteractions.size <= 16 && unlockedActions.size <= 32 && achievements.size <= 32 && settledGameIds.size <= 64)
        require(unlockedActions.all { it.matches(Regex("[a-z_]{1,32}")) })
        require(bestScores.keys.all { it in GAME_IDS } && bestScores.values.all { it in 0..1_000_000 })
    }

    fun recover(now: Long): PetState {
        if (now <= 0 || now < lastRecoveryTime) return this
        if (lastRecoveryTime == 0L) return copy(lastRecoveryTime = now)
        val steps = ((now - lastRecoveryTime) / 300_000).coerceAtMost(100).toInt()
        return if (steps == 0) this else copy(energy = (energy + steps).coerceAtMost(100), lastRecoveryTime = now)
    }

    fun interact(type: InteractionType, now: Long): PetState {
        val time = maxOf(now, lastInteractionTime, 0)
        val recovered = recover(time)
        val rewarded = interactionCount == 0 || time - lastRewardTime >= 2000
        val count = (interactionCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val unlocked = achievements.toMutableSet().apply {
            add("first_touch")
            if (count >= 50) add("fifty_touches")
            if (type == InteractionType.COMBO) add("combo_friend")
        }
        return recovered.copy(
            happiness = (happiness + if (rewarded) 2 else 0).coerceAtMost(100),
            energy = (recovered.energy - if (rewarded && type != InteractionType.DRAG) 1 else 0).coerceAtLeast(0),
            affinity = (affinity + if (rewarded && count % 5 == 0) 1 else 0).coerceAtMost(100),
            lastInteractionTime = time,
            interactionCount = count,
            recentInteractions = (recentInteractions + InteractionRecord(type, time)).takeLast(16),
            achievements = unlocked,
            lastRewardTime = if (rewarded) time else lastRewardTime,
        )
    }

    fun discover(action: String): PetState {
        require(action.matches(Regex("[a-z_]{1,32}")))
        if (action in unlockedActions || unlockedActions.size >= 32) return this
        val found = unlockedActions + action
        return copy(unlockedActions = found,
            achievements = achievements + if (found.size >= 10) setOf("ten_actions") else emptySet())
    }

    fun settleGame(id: String, game: String, score: Int, now: Long): PetState {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && game in GAME_IDS && score in 0..1_000_000)
        if (id in settledGameIds) return this
        val next = interact(InteractionType.GAME, now)
        val best = bestScores + (game to maxOf(bestScores[game] ?: 0, score))
        val awards = next.achievements.toMutableSet().apply {
            add("first_game")
            if (best.keys.containsAll(GAME_IDS)) add("all_games")
            if (game == "bubbles" && score >= 100) add("bubble_catcher")
            if (game == "drum" && score >= 1000) add("rhythm_friend")
        }
        return next.copy(bestScores = best, settledGameIds = (settledGameIds + id).takeLast(64),
            happiness = (next.happiness + 3).coerceAtMost(100),
            affinity = (next.affinity + 1).coerceAtMost(100), achievements = awards)
    }

    companion object { val GAME_IDS = setOf("drum", "combo", "bubbles") }
}

object AchievementCatalog {
    val entries = linkedMapOf(
        "first_touch" to ("初次见面" to "和奶蛙互动一次"),
        "combo_friend" to ("有点上头" to "在桌宠上连续轻点六次"),
        "fifty_touches" to ("熟悉的手指" to "累计互动五十次"),
        "ten_actions" to ("表情收藏家" to "见过十个合格动作"),
        "first_game" to ("一起玩吧" to "完成一局小游戏"),
        "all_games" to ("游乐场常客" to "三个小游戏都玩一局"),
        "bubble_catcher" to ("奶泡接住了" to "接奶泡达到一百分"),
        "rhythm_friend" to ("肚皮节拍手" to "肚皮打鼓达到一千分"),
    )
}
