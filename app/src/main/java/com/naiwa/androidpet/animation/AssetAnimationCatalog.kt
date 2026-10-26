package com.naiwa.androidpet.animation

import android.content.res.AssetManager
import org.json.JSONObject

data class SpriteLayout(val width: Int, val height: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 16_777_216L) { "画布最大 4096px / 1600 万像素" }
        require(left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top) { "contentBounds 必须在画布内且非空" }
    }
    val contentWidth: Int get() = right - left
    val contentHeight: Int get() = bottom - top
    fun scaledViewport(referencePixels: Int, referenceHeight: Int): com.naiwa.androidpet.core.PetSize {
        require(referencePixels > 0 && referenceHeight in 1..contentHeight)
        val scale = minOf(referencePixels.toDouble()/referenceHeight,
            16384.0/maxOf(contentWidth,contentHeight))
        return com.naiwa.androidpet.core.PetSize(
            kotlin.math.round(contentWidth*scale).toInt().coerceAtLeast(1),
            kotlin.math.round(contentHeight*scale).toInt().coerceAtLeast(1))
    }
}

data class AssetAnimationCatalog(
    val clips: Map<PetAction, AnimationClip>,
    val layout: SpriteLayout,
    val placeholder: Boolean,
    val diagnostics: List<String>,
    val frameLayouts: Map<String, SpriteLayout> = emptyMap(),
    val temporaryActions: Set<PetAction> = emptySet(),
    val referenceHeight: Int = layout.contentHeight,
) {
    companion object {
        private val fallback = SpriteLayout(256, 256, 0, 0, 256, 256)
        fun load(assets: AssetManager): AssetAnimationCatalog {
            val diagnostics = mutableListOf<String>()
            val config = try {
                val text = assets.open("naiwa/animations.json").bufferedReader().use { reader ->
                    val buffer = CharArray(65_537)
                    var used = 0
                    while (used < buffer.size) {
                        val count = reader.read(buffer, used, buffer.size - used)
                        if (count == -1) break
                        used += count
                    }
                    require(used <= 65_536) { "animations.json 超过 64KiB" }
                    String(buffer, 0, used)
                }
                JSONObject(text)
            } catch (error: Exception) {
                return AssetAnimationCatalog(emptyMap(), fallback, true,
                    listOf("无法读取 assets/naiwa/animations.json：${error.message}"))
            }
            val layout = try {
                val canvas = config.getJSONObject("canvas")
                val crop = config.getJSONObject("contentBounds")
                SpriteLayout(canvas.getInt("width"), canvas.getInt("height"), crop.getInt("left"), crop.getInt("top"), crop.getInt("right"), crop.getInt("bottom"))
            } catch (error: Exception) {
                return AssetAnimationCatalog(emptyMap(), fallback, true, listOf("素材画布配置错误：${error.message}"))
            }
            val clips = mutableMapOf<PetAction, AnimationClip>()
            val frameLayouts = mutableMapOf<String, SpriteLayout>()
            val temporaryActions = mutableSetOf<PetAction>()
            for (action in PetAction.entries) {
                val folder = "naiwa/${action.directory}"
                try {
                    val actions = config.getJSONObject("actions")
                    if (action.optional && !actions.has(action.directory)) continue
                    val frames = assets.list(folder).orEmpty().filter { it.endsWith(".png", ignoreCase = true) }.sortedWith(NaturalOrder)
                    val settings = actions.getJSONObject(action.directory)
                    val loop = settings.getBoolean("loop")
                    require(action == PetAction.IDLE || (action == PetAction.WALK && loop) ||
                        (action != PetAction.WALK && !loop)) { "仅 idle/walk 可循环，walk 必须循环" }
                    val actionLayout = if (settings.has("canvas") || settings.has("contentBounds")) {
                        val canvas = settings.getJSONObject("canvas")
                        val crop = settings.getJSONObject("contentBounds")
                        SpriteLayout(canvas.getInt("width"), canvas.getInt("height"), crop.getInt("left"), crop.getInt("top"), crop.getInt("right"), crop.getInt("bottom"))
                    } else layout
                    val paths = frames.map { "$folder/$it" }
                    clips[action] = AnimationClip(action, paths, settings.getInt("fps"), loop,
                        if (settings.has("lastFrameHoldMillis")) settings.getLong("lastFrameHoldMillis") else 0,
                        settings.optJSONArray("frameDurationsMillis")?.let { timing ->
                            List(timing.length()) { timing.getLong(it) }
                        } ?: emptyList(), settings.optBoolean("interruptible", true))
                    if (settings.optBoolean("temporary",config.optBoolean("placeholder",true))) temporaryActions.add(action)
                    paths.forEach { frameLayouts[it] = actionLayout }
                } catch (error: Exception) {
                    diagnostics.add("动作不可用：assets/$folder/；${error.message}")
                }
            }
            val referenceHeight = config.optInt("referenceHeight",layout.contentHeight)
            if (referenceHeight !in 1..layout.contentHeight)
                return AssetAnimationCatalog(emptyMap(),layout,true,listOf("参考角色高度超出画布"))
            return AssetAnimationCatalog(clips, layout, config.optBoolean("placeholder", true), diagnostics, frameLayouts,
                temporaryActions,referenceHeight)
        }
    }
}
