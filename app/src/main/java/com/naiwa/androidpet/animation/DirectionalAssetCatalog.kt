package com.naiwa.androidpet.animation

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import org.json.JSONObject

/** Optional turntable assets have an independent manifest and never replace formal front art. */
data class DirectionalAssetCatalog(
    val clips: Map<PetDirection, Map<PetAction, AnimationClip>> = emptyMap(),
    val layouts: Map<String, SpriteLayout> = emptyMap(),
) {
    companion object {
        fun load(assets: AssetManager, diagnostics: MutableList<String>): DirectionalAssetCatalog {
            if ("directions.json" !in assets.list("naiwa").orEmpty()) return DirectionalAssetCatalog()
            val clips = mutableMapOf<PetDirection, Map<PetAction, AnimationClip>>()
            val layouts = mutableMapOf<String, SpriteLayout>()
            try {
                val json = assets.open("naiwa/directions.json").bufferedReader().use { reader ->
                    val buffer = CharArray(65_537)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    require(count <= 65_536) { "directions.json 超过 64KiB" }
                    JSONObject(String(buffer, 0, count))
                }
                require(json.getInt("schemaVersion") == 1) { "不支持的方向配置版本" }
                val canvas = json.getJSONObject("canvas")
                val crop = json.getJSONObject("contentBounds")
                val layout = SpriteLayout(canvas.getInt("width"), canvas.getInt("height"),
                    crop.getInt("left"), crop.getInt("top"), crop.getInt("right"), crop.getInt("bottom"))
                val directions = json.getJSONObject("directions")
                for (direction in PetDirection.entries.filter { it != PetDirection.FRONT }) {
                    if (!directions.has(direction.name)) continue
                    try {
                        val actions = directions.getJSONObject(direction.name).getJSONObject("actions")
                        val loaded = mutableMapOf<PetAction, AnimationClip>()
                        val frameLayouts = mutableMapOf<String, SpriteLayout>()
                        for (action in PetAction.entries) {
                            if (!actions.has(action.directory)) continue
                            val settings = actions.getJSONObject(action.directory)
                            val files = settings.getJSONArray("frames")
                            require(files.length() in 1..120) { "方向动作须有 1–120 帧" }
                            val paths = List(files.length()) { index ->
                                val relative = files.getString(index)
                                require(relative.matches(Regex("directions/${direction.directory}/${action.directory}/[a-z0-9_]+\\.png"))) {
                                    "方向资源路径无效"
                                }
                                val path = "naiwa/$relative"
                                val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                assets.open(path).use { BitmapFactory.decodeStream(it, null, header) }
                                require(header.outMimeType == "image/png" && header.outWidth == layout.width && header.outHeight == layout.height) {
                                    "方向 PNG 画布错误：$path"
                                }
                                path
                            }
                            require(paths.distinct().size == paths.size) { "方向帧不能重复" }
                            val loop = settings.getBoolean("loop")
                            require(if (action in setOf(PetAction.IDLE, PetAction.WALK)) loop else !loop) { "方向循环设置无效" }
                            loaded[action] = AnimationClip(action, paths, settings.getInt("fps"), loop,
                                settings.optLong("lastFrameHoldMillis", 0),
                                settings.optJSONArray("frameDurationsMillis")?.let { times -> List(times.length()) { times.getLong(it) } }
                                    ?: emptyList(), settings.optBoolean("interruptible", true))
                            paths.forEach { frameLayouts[it] = layout }
                        }
                        require(PetAction.IDLE in loaded) { "方向缺少 idle" }
                        clips[direction] = loaded; layouts.putAll(frameLayouts)
                    } catch (error: Exception) {
                        diagnostics.add("方向 ${direction.name} 暂用正面：${error.message}")
                    }
                }
            } catch (error: Exception) {
                diagnostics.add("方向配置不可用，保留正面动作：${error.message}")
            }
            return DirectionalAssetCatalog(clips, layouts)
        }
    }
}
