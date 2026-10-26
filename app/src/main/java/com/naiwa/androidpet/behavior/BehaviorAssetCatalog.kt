package com.naiwa.androidpet.behavior

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.naiwa.androidpet.animation.AssetAnimationCatalog
import java.security.MessageDigest

/** Formal approval is tied to the exact reviewed bytes, not just a directory name. */
object BehaviorAssetCatalog {
    fun createPool(assets: AssetManager,catalog: AssetAnimationCatalog,config: BehaviorConfig): ActionPool {
        val facts = config.policies.mapValues { (action,rule) ->
            val clip = catalog.clips[action]
            val temporary = action in catalog.temporaryActions
            val authorized = rule.source.declared && try {
                val evidence = assets.open(rule.source.evidencePath).use { it.readBytesCapped(65_536) }
                evidence.isNotEmpty() && MessageDigest.getInstance("SHA-256").digest(evidence)
                    .joinToString("") { "%02x".format(it) }.equals(rule.source.evidenceSha256, true)
            } catch (_: Exception) { false }
            val reviewAllowed = rule.materialStatus == MaterialStatus.FORMAL && rule.qualityApproved &&
                (rule.allowAutomatic || rule.allowManual) && !temporary && authorized
            val validated = clip != null && reviewAllowed && try {
                ApprovedFrameValidator.verify(clip.frames,rule.sha256,
                    { path -> assets.open(path).use { it.readBytesCapped(16_777_216) } },
                    { path,bytes ->
                        val layout = catalog.frameLayouts[path] ?: catalog.layout
                        validatePng(bytes,layout.width,layout.height)
                    })
            } catch (_: OutOfMemoryError) { false }
            ActionAssetFacts(clip != null,validated,temporary,authorized)
        }
        return ActionPool(config.policies,facts)
    }

    private fun validatePng(bytes: ByteArray,width: Int,height: Int): Boolean {
        val signature = byteArrayOf(-119,80,78,71,13,10,26,10)
        if (bytes.size < 33 || !bytes.copyOfRange(0,8).contentEquals(signature) ||
            bytes[24].toInt() != 8 || bytes[25].toInt() != 6 || bytes[28].toInt() != 0) return false
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,header)
        if (header.outMimeType != "image/png" || header.outWidth != width || header.outHeight != height) return false
        var sample = 1
        while (maxOf(width,height)/sample > 128) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: return false
        return try {
            val pixels = IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            bitmap.hasAlpha() && pixels.any { it ushr 24 == 0 } && pixels.any { it ushr 24 == 255 }
        } finally { bitmap.recycle() } // Validation-only bitmap; never handed to a View.
    }
}
