package com.naiwa.androidpet.behavior

import com.naiwa.androidpet.animation.PetAction

fun interface BehaviorRandom { fun nextInt(bound: Int): Int }
enum class MaterialStatus { FORMAL, TEMPORARY, MISSING }
data class ActionPolicy(val action: PetAction, val weight: Int, val materialStatus: MaterialStatus,
    val qualityApproved: Boolean, val allowAutomatic: Boolean, val allowManual: Boolean,
    val sha256: Map<String, String> = emptyMap(), val cooldownMillis: Long = 0,
    val source: MaterialSource = MaterialSource()) {
    init {
        require(weight in 0..10_000) { "${action.directory}: weight must be 0–10000" }
        require(cooldownMillis in 0..86_400_000L) { "${action.directory}: invalid cooldown" }
    }
}
/** Personal reference-derived work is scoped to this user's private offline installation. */
data class MaterialSource(val usageScope: String = "unverified", val origin: String = "",
    val evidencePath: String = "", val evidenceSha256: String = "") {
    val declared: Boolean get() = usageScope in setOf("private-personal", "licensed") &&
        origin.isNotBlank() && evidencePath.matches(Regex("naiwa/notices/[a-zA-Z0-9_-]+\\.txt")) &&
        evidenceSha256.matches(Regex("[0-9a-fA-F]{64}"))
}

data class ActionAssetFacts(val present: Boolean, val validated: Boolean, val temporary: Boolean,
    val authorizationVerified: Boolean = false)

class ActionPool(policies: Map<PetAction, ActionPolicy>, facts: Map<PetAction, ActionAssetFacts>) {
    val policies = policies.toMap()
    private val facts = facts.toMap()
    private val disabled = mutableSetOf<PetAction>()
    init { require(this.policies.all { it.key == it.value.action }) }

    val automaticActions: Set<PetAction> get() = policies.values.filter { rule ->
        rule.action != PetAction.IDLE && rule.weight > 0 && blockedReason(rule.action) == null
    }.mapTo(linkedSetOf()) { it.action }

    fun displayBlockedReason(action: PetAction): String? {
        val rule = policies[action] ?: return "未配置动作"
        val asset = facts[action]
        return when {
            action in disabled -> "播放校验失败，本次运行已禁用"
            asset?.present != true || rule.materialStatus == MaterialStatus.MISSING -> "缺少素材"
            rule.materialStatus != MaterialStatus.FORMAL || asset.temporary -> "临时素材，不进入自动池"
            !asset.authorizationVerified -> "缺少与当前使用范围匹配的素材来源记录"
            !rule.qualityApproved -> "尚未通过正式素材质量验收"
            !asset.validated -> "素材校验或已批准哈希不匹配"
            else -> null
        }
    }

    fun blockedReason(action: PetAction): String? = displayBlockedReason(action) ?:
        if (policies[action]?.allowAutomatic == true) null else "未明确允许自动使用"

    fun canDisplay(action: PetAction): Boolean = displayBlockedReason(action) == null

    fun canManualFormal(action: PetAction): Boolean = canDisplay(action) && canManual(action)

    /** Legacy diagnostic preview eligibility; the production player also requires canDisplay. */
    fun canManual(action: PetAction): Boolean = action !in disabled &&
        policies[action]?.allowManual == true && facts[action]?.present == true

    fun choose(random: BehaviorRandom, allowed: (PetAction) -> Boolean = { true }): PetAction? {
        val candidates = automaticActions.filter(allowed)
        val total = candidates.sumOf { policies.getValue(it).weight }
        if (total == 0) return null
        var ticket = random.nextInt(total)
        require(ticket in 0 until total)
        for (action in candidates) {
            ticket -= policies.getValue(action).weight
            if (ticket < 0) return action
        }
        error("Invalid weighted ticket")
    }

    fun disable(action: PetAction) { disabled.add(action) }
}
