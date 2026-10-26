package com.naiwa.androidpet.animation

import android.content.Context
import com.naiwa.androidpet.behavior.*

data class ApprovedMaterials(val catalog: AssetAnimationCatalog, val behavior: BehaviorConfig, val pool: ActionPool)

/** Exact-byte validation once per application process. No Activity, View or Bitmap is cached here. */
object PetMaterials {
    @Volatile private var cached: ApprovedMaterials? = null
    fun load(context: Context): ApprovedMaterials = cached ?: synchronized(this) {
        cached ?: run {
            val assets = context.applicationContext.assets
            val catalog = AssetAnimationCatalog.load(assets)
            val behavior = BehaviorConfig.load(assets)
            ApprovedMaterials(catalog,behavior,BehaviorAssetCatalog.createPool(assets,catalog,behavior)).also { cached = it }
        }
    }
}
