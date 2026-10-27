package com.naiwa.androidpet.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.naiwa.androidpet.runtime.PetRuntime
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val done: Long, val total: Long) : DownloadState
    data object Cancelling : DownloadState
    data object Ready : DownloadState
    data object Cancelled : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/** Application-scoped update coordinator; it never controls the pet service or its data. */
class UpdateManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("naiwa_updates", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val checkExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val checkBusy = AtomicBoolean(false)
    private val downloadBusy = AtomicBoolean(false)
    private val cancelDownload = AtomicBoolean(false)
    private val changePending = AtomicBoolean(false)
    private val listeners = linkedSetOf<() -> Unit>()
    private val transport: UpdateTransport = GitHubHttpTransport()
    private val store = object : UpdateStore {
        override var lastCheck: Long
            get() = prefs.getLong("last_check", 0)
            set(value) { prefs.edit().putLong("last_check", value).apply() }
        override var ignoredVersion: Long
            get() = prefs.getLong("ignored_version", 0)
            set(value) { prefs.edit().putLong("ignored_version", value).apply() }
        override var notifiedVersion: Long
            get() = prefs.getLong("notified_version", 0)
            @android.annotation.SuppressLint("ApplySharedPref") // A posted version must survive process death for deduplication.
            set(value) { prefs.edit().putLong("notified_version", value).commit() }
    }
    private fun releaseSource(http: HttpTransport) = UpdateSource {
        val installed = ApkIdentity.installedInfo(app)
        val key = ApkIdentity.publicKey(installed)
        if (key == null) CheckResult.Unavailable("无法读取当前应用的单一签名证书，暂不能检查可信更新。")
        else GitHubReleaseSource(http, key, app.packageName, ApkIdentity.version(installed)).check()
    }
    private val source = releaseSource(transport)
    private val coordinator = UpdateCoordinator(source, store, System::currentTimeMillis) { result ->
        state = result
        changed()
    }
    @Volatile var state: CheckResult = CheckResult.Idle
        private set
    @Volatile var release: UpdateRelease? = null
        private set
    @Volatile var downloadState: DownloadState = DownloadState.Idle
        private set
    @Volatile var installFeedback: String? = null
        private set
    @Volatile var schedulingError: String? = null
        private set
    val lastCheck: Long get() = store.lastCheck
    val ignoredVersion: Long get() = store.ignoredVersion
    val snoozedVersion: Long get() = prefs.getLong("snoozed_version", 0)
    val snoozeUntil: Long get() = prefs.getLong("snooze_until", 0)
    val automatic: Boolean get() = prefs.getBoolean("automatic", false)
    val notifications: Boolean get() = prefs.getBoolean("notifications", false)
    val apkFile: File get() = File(app.filesDir, "updates/update.apk")

    init {
        val cached = restoreRelease()
        release = cached
        val kind = prefs.getString("status_kind", "") ?: ""
        state = if (kind == "available" && cached != null) CheckResult.Available(cached)
            else UpdateStatusCodec.decode(kind, prefs.getString("status_reason", "") ?: "", lastCheck)
    }

    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() {
        if (changePending.compareAndSet(false, true)) main.post {
            changePending.set(false); listeners.toList().forEach { it() }
        }
    }

    fun start() {
        configureSchedule()
        val elapsed = System.currentTimeMillis() - lastCheck
        if (lastCheck == 0L || (automatic && (elapsed < 0 || elapsed >= 6 * 60 * 60 * 1000L))) requestCheck()
    }
    private fun configureSchedule() {
        schedulingError = if (UpdateJobs.configure(app, automatic) || !automatic) null
            else "系统暂时无法安排后台检查，可重试或手动检查。"
        changed()
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        configureSchedule()
        if (enabled) requestCheck()
        changed()
    }
    fun setNotifications(enabled: Boolean) {
        prefs.edit().putBoolean("notifications", enabled).apply()
        if (!enabled) UpdateNotifications.cancel(app)
        changed()
    }
    fun ignoreCurrent() {
        release?.let { store.ignoredVersion = it.manifest.versionCode }
        UpdateNotifications.cancel(app); changed()
    }
    fun remindLater() {
        release?.let { prefs.edit().putLong("snoozed_version", it.manifest.versionCode)
            .putLong("snooze_until", System.currentTimeMillis() + 24 * 60 * 60 * 1000L).apply() }
        UpdateNotifications.cancel(app); changed()
    }
    fun restoreReminders() {
        store.ignoredVersion = 0
        prefs.edit().remove("snoozed_version").remove("snooze_until").apply()
        changed()
    }
    fun setInstallFeedback(message: String?) { installFeedback = message; changed() }

    fun requestCheck() {
        if (!checkBusy.compareAndSet(false, true)) return
        checkExecutor.execute { runCheck { true } }
    }
    /** Called on the job's worker thread; false means another check is already in flight. */
    fun checkFromJob(jobTransport: HttpTransport? = null, stillRunning: () -> Boolean): Boolean {
        if (!automatic || !checkBusy.compareAndSet(false, true)) return false
        runCheck(jobTransport?.let(::releaseSource)) { automatic && stillRunning() }
        return true
    }
    private fun runCheck(overrideSource: UpdateSource? = null, mayNotify: () -> Boolean) {
        try {
            coordinator.check(overrideSource)
            val result = coordinator.state
            val savedStatus = UpdateStatusCodec.encode(result)
            prefs.edit().putString("status_kind", savedStatus.first).putString("status_reason", savedStatus.second).apply()
            when (result) {
                is CheckResult.Available -> {
                    replaceRelease(result.release)
                    if (!saveRelease(result.release)) {
                        release = null; state = CheckResult.Failure("无法保存更新信息，请重试。")
                        prefs.edit().putString("status_kind", "failure").putString("status_reason", "无法保存更新信息，请重试。").apply()
                        changed(); return
                    }
                    val interrupted = Thread.currentThread().isInterrupted
                    main.post {
                        if (mayNotify() && !interrupted)
                            UpdateNotifications.notifyIfAllowed(app, result.release, notifications, PetRuntime.appVisible,
                                store, snoozedVersion, snoozeUntil)
                        changed()
                    }
                }
                CheckResult.UpToDate, is CheckResult.Unavailable -> {
                    replaceRelease(null); prefs.edit().remove("cached_release").apply(); changed()
                }
                else -> Unit
            }
        } catch (_: Exception) {
            state = CheckResult.Failure("检查更新失败，请稍后重试。")
            changed()
        } finally { checkBusy.set(false) }
    }

    private fun replaceRelease(next: UpdateRelease?) {
        val previous = release?.manifest
        val target = next?.manifest
        if (previous?.versionCode != target?.versionCode || previous?.sha256 != target?.sha256) {
            installFeedback = null
            if (downloadBusy.get()) {
                cancelDownload.set(true)
                downloadState = DownloadState.Cancelling
            } else downloadState = DownloadState.Idle
        }
        release = next
    }

    fun download() {
        val selected = release ?: return
        if (!downloadBusy.compareAndSet(false, true)) return
        installFeedback = null
        cancelDownload.set(false)
        downloadState = DownloadState.Downloading(0, selected.manifest.size); changed()
        downloadExecutor.execute {
            try {
                var lastProgress = 0L
                val result = ArtifactDownloader(apkFile) { transport.openDownload(selected.asset.url) }
                    .download(selected.manifest.size, selected.manifest.sha256, cancelDownload::get) { done, total ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!cancelDownload.get() && (now - lastProgress >= 250 || done == total)) {
                            lastProgress = now; downloadState = DownloadState.Downloading(done, total); changed()
                        }
                    }
                downloadState = when (result) {
                    DownloadResult.Cancelled -> DownloadState.Cancelled
                    is DownloadResult.Failed -> DownloadState.Failed(result.reason)
                    DownloadResult.Ready -> {
                        val error = ApkIdentity.validate(app, apkFile, selected)
                        when {
                            release?.manifest?.sha256 != selected.manifest.sha256 -> DownloadState.Failed("发布版本已变化，请重新检查。")
                            error != null -> { apkFile.delete(); DownloadState.Failed(error) }
                            else -> DownloadState.Ready
                        }
                    }
                }
            } catch (_: Exception) {
                downloadState = DownloadState.Failed("下载失败，请稍后重试。")
            } finally { downloadBusy.set(false); changed() }
        }
    }
    fun cancelDownload() {
        if (downloadBusy.get()) { cancelDownload.set(true); downloadState = DownloadState.Cancelling; changed() }
    }

    /** Re-check immediately before handing a URI to the system installer. */
    fun validateForInstall(callback: (String?) -> Unit) {
        val selected = release ?: return callback("请先检查更新。")
        if (downloadBusy.get()) return callback("下载尚未完成。")
        downloadExecutor.execute {
            val error = ApkIdentity.validate(app, apkFile, selected)
            if (error != null) { apkFile.delete(); downloadState = DownloadState.Failed(error); changed() }
            main.post { callback(error) }
        }
    }
    fun validFileForProvider(): File? {
        val selected = release ?: return null
        return if (ApkIdentity.validate(app, apkFile, selected) == null) apkFile else null
    }

    @android.annotation.SuppressLint("ApplySharedPref") // The notification may reopen a freshly killed process.
    private fun saveRelease(value: UpdateRelease): Boolean {
        val json = JSONObject().put("manifest", Base64.encodeToString(value.signedManifest, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(value.signature, Base64.NO_WRAP))
            .put("asset", value.asset.name).put("size", value.asset.size).put("url", value.asset.url)
            .put("published", value.publishedAt)
        return prefs.edit().putString("cached_release", json.toString()).commit()
    }
    private fun restoreRelease(): UpdateRelease? {
      return try {
        val json = JSONObject(prefs.getString("cached_release", null) ?: return null)
        val bytes = Base64.decode(json.getString("manifest"), Base64.NO_WRAP)
        val signature = Base64.decode(json.getString("signature"), Base64.NO_WRAP)
        val installed = ApkIdentity.installedInfo(app)
        val key = ApkIdentity.publicKey(installed) ?: return null
        if (!UpdatePolicy.verifyMetadata(bytes, signature, key)) return null
        val manifest = GitHubReleaseSource.parseManifest(bytes)
        val asset = ReleaseAsset(json.getString("asset"), json.getLong("size"), json.getString("url"))
        if (!UpdatePolicy.validManifest(manifest, manifest.tag, app.packageName, asset) ||
            !UpdatePolicy.isNewer(ApkIdentity.version(installed), manifest.versionCode)) return null
        UpdateRelease(manifest, asset, json.optString("published", ""), bytes, signature)
      } catch (_: Exception) { null }
    }

    companion object {
        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context).also { instance = it }
        }
    }
}
