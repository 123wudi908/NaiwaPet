package com.naiwa.androidpet.animation

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.naiwa.androidpet.overlay.PetFrameView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.naiwa.androidpet.runtime.PetDiagnostics

/** At most one decode + one queued request; decoded image references are capped by an 8MiB LRU. */
class PngFrameSink(
    private val assets: AssetManager,
    private val layout: SpriteLayout,
    private val view: PetFrameView,
    private val frameLayouts: Map<String, SpriteLayout> = emptyMap(),
) : FrameSink {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), { runnable -> Thread(runnable, "NaiwaPngDecoder").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var generation = 0L
    private var closed = false
    private var targetPixels = 256

    fun resize(pixels: Int) {
        val target = pixels.coerceIn(1, 1024)
        synchronized(lock) {
            if (target == targetPixels) return
            targetPixels = target
            cache.evictAll()
        }
    }

    override fun present(path: String, completion: (Result<Unit>) -> Unit) {
        val ticket: Long
        val target: Int
        val cached: Bitmap?
        synchronized(lock) {
            if (closed) return
            ticket = ++generation; target = targetPixels; cached = cache.get(path)
        }
        if (cached != null) { deliver(ticket, path, cached, completion); return }
        executor.execute {
            val result = try { Result.success(decode(path, target)) }
            catch (error: Exception) { PetDiagnostics.event("frame.decode_failed", path, error); Result.failure(error) }
            catch (_: OutOfMemoryError) { Result.failure(IllegalStateException("解码内存不足；请缩小 PNG 画布和角色尺寸")) }
            synchronized(lock) {
                if (closed || generation != ticket) return@execute
                result.getOrNull()?.let { cache.put(path, it) }
            }
            main.post {
                if (!isCurrent(ticket)) return@post
                result.fold(
                    onSuccess = { showFrame(path, it); completion(Result.success(Unit)) },
                    onFailure = { completion(Result.failure(it)) },
                )
            }
        }
    }

    private fun deliver(ticket: Long, path: String, bitmap: Bitmap, completion: (Result<Unit>) -> Unit) {
        main.post {
            if (isCurrent(ticket)) { showFrame(path, bitmap); completion(Result.success(Unit)) }
        }
    }

    private fun showFrame(path: String, bitmap: Bitmap) {
        val placement = frameLayouts[path] ?: layout
        // Fractions keep source-pixel calibration stable through decoder sampling and View resizing.
        // Apply on cache hits too, so an unshifted IDLE resets the previous action's placement.
        view.setFrame(bitmap, placement.renderOffsetX.toFloat() / placement.contentWidth,
            placement.renderOffsetY.toFloat() / placement.contentHeight)
    }

    private fun isCurrent(ticket: Long) = synchronized(lock) { !closed && generation == ticket }

    @Suppress("DEPRECATION") // Public region decoder supports our API 26 floor and avoids decoding large transparent margins.
    private fun decode(path: String, target: Int): Bitmap {
        val layout = frameLayouts[path] ?: this.layout
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path, AssetManager.ACCESS_STREAMING).use { BitmapFactory.decodeStream(it, null, header) }
        require(header.outMimeType == "image/png") { "文件不是 PNG：$path" }
        require(header.outWidth == layout.width && header.outHeight == layout.height) {
            "画布不一致：$path 为 ${header.outWidth}×${header.outHeight}，应为 ${layout.width}×${layout.height}"
        }
        val plan = DecodeSizing.plan(layout.contentWidth,layout.contentHeight,target)
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }
        val bitmap = assets.open(path, AssetManager.ACCESS_STREAMING).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream, false)
                ?: throw IllegalArgumentException("无法创建 PNG 区域解码器：$path")
            try { decoder.decodeRegion(Rect(layout.left, layout.top, layout.right, layout.bottom), options) }
            finally { decoder.recycle() }
        } ?: throw IllegalArgumentException("PNG 解码失败：$path")
        require(bitmap.hasAlpha()) { "PNG 缺少 Alpha 透明通道：$path" }
        if (bitmap.width == plan.width && bitmap.height == plan.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap,plan.width,plan.height,true)
        // This decoded source has never reached the View, cache or render thread.
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    override fun cancelPending() {
        synchronized(lock) { generation++ }
        executor.queue.clear()
        main.removeCallbacksAndMessages(null)
    }

    override fun clear() {
        cancelPending()
        synchronized(lock) { cache.evictAll() }
        view.clearFrame()
        // Do not recycle a Bitmap still referenced by Android's render thread; release references for GC.
    }

    fun trimMemory() { synchronized(lock) { cache.evictAll() } }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        clear()
        executor.shutdownNow()
    }
}
