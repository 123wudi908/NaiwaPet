package com.naiwa.androidpet.update

import java.util.concurrent.atomic.AtomicBoolean

data class UpdateRelease(val manifest: ReleaseManifest, val asset: ReleaseAsset, val publishedAt: String,
    val signedManifest: ByteArray, val signature: ByteArray)

sealed interface CheckResult {
    data object Idle : CheckResult
    data object Checking : CheckResult
    data object UpToDate : CheckResult
    data class Available(val release: UpdateRelease) : CheckResult
    data class Unavailable(val reason: String) : CheckResult
    data class Failure(val reason: String) : CheckResult
}

fun interface UpdateSource { fun check(): CheckResult }
interface UpdateStore {
    var lastCheck: Long
    var ignoredVersion: Long
    var notifiedVersion: Long
}

/** Shared by startup, manual and background requests; only one source call can run at once. */
class UpdateCoordinator(private val source: UpdateSource, private val store: UpdateStore,
    private val clock: () -> Long, private val changed: (CheckResult) -> Unit = {}) {
    private val checking = AtomicBoolean(false)
    @Volatile var state: CheckResult = CheckResult.Idle
        private set

    fun check(overrideSource: UpdateSource? = null): Boolean {
        if (!checking.compareAndSet(false, true)) return false
        publish(CheckResult.Checking)
        try {
            val result = try { (overrideSource ?: source).check() } catch (_: Exception) {
                CheckResult.Failure("网络连接失败，请检查网络后重试。")
            }
            store.lastCheck = clock()
            publish(result)
        } finally { checking.set(false) }
        return true
    }

    private fun publish(value: CheckResult) { state = value; changed(value) }
}
