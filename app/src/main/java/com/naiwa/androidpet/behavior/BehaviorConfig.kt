package com.naiwa.androidpet.behavior

import android.content.res.AssetManager
import com.naiwa.androidpet.animation.PetAction
import org.json.JSONObject
import java.util.Locale

data class BehaviorConfig(val settings: BehaviorSettings, val policies: Map<PetAction,ActionPolicy>,
    val diagnostics: List<String> = emptyList()) {
    companion object {
        fun load(assets: AssetManager): BehaviorConfig = try {
            val bytes = assets.open("naiwa/behavior.json").use { it.readBytesCapped(262_144) }
            val json = JSONObject(String(bytes,Charsets.UTF_8))
            require(json.getInt("schemaVersion") == 2) { "不支持的行为配置版本" }
            val automatic = json.getJSONObject("automaticBehavior")
            val movement = json.getJSONObject("automaticMovement")
            val settings = BehaviorSettings(automatic.getLong("minIntervalMillis"),automatic.getLong("maxIntervalMillis"),
                movement.getLong("minIntervalMillis"),movement.getLong("maxIntervalMillis"),
                json.getJSONObject("interaction").getLong("resumeDelayMillis"),movement.getDouble("speedDpPerSecond"),
                automatic.getBoolean("enabledByDefault"),movement.getBoolean("enabledByDefault"))
            val actions = json.getJSONObject("actions")
            val policies = actions.keys().asSequence().associate { key ->
                val action = PetAction.entries.firstOrNull { it.directory == key }
                    ?: throw IllegalArgumentException("未知动作：$key")
                val rule = actions.getJSONObject(key)
                val hashes = rule.getJSONObject("sha256")
                val origin = rule.optJSONObject("source")
                action to ActionPolicy(action,rule.getInt("weight"),
                    MaterialStatus.valueOf(rule.getString("materialStatus").uppercase(Locale.ROOT)),
                    rule.getBoolean("qualityApproved"),rule.getBoolean("allowAutomatic"),rule.getBoolean("allowManual"),
                    hashes.keys().asSequence().associateWith { hashes.getString(it) },
                    rule.optLong("cooldownMillis", 0), MaterialSource(
                        origin?.optString("usageScope", "unverified") ?: "unverified",
                        origin?.optString("origin", "") ?: "",
                        origin?.optString("evidencePath", "") ?: "",
                        origin?.optString("evidenceSha256", "") ?: ""))
            }
            BehaviorConfig(settings,policies)
        } catch (error: Exception) {
            BehaviorConfig(BehaviorSettings(automaticByDefault = false,movementByDefault = false),emptyMap(),
                listOf("自动行为配置不可用，已禁用：${error.message}"))
        }
    }
}

internal fun java.io.InputStream.readBytesCapped(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) break
        require(buffer.size()+count <= limit) { "素材或配置文件过大" }
        buffer.write(chunk,0,count)
    }
    return buffer.toByteArray()
}
