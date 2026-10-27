package com.naiwa.androidpet

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import com.naiwa.androidpet.ui.AppPage
import com.naiwa.androidpet.ui.CompanionUi
import com.naiwa.androidpet.update.*
import java.io.File
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-only UI fixtures. They are injected in memory and are never packaged in the app APK. */
class UpdateProbe(private val instrumentation: Instrumentation) {
    private val targetContext get() = instrumentation.targetContext
    private val uiAutomation get() = instrumentation.uiAutomation
    private fun startActivitySync(intent: Intent) = instrumentation.startActivitySync(intent)
    private fun runOnMainSync(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitForIdleSync() = instrumentation.waitForIdleSync()
    private fun sendStatus(code: Int, bundle: Bundle) = instrumentation.sendStatus(code, bundle)
    private fun finish(code: Int, bundle: Bundle) = instrumentation.finish(code, bundle)
    fun run() {
        val failures = mutableListOf<String>()
        var passed = 0
        var activity: MainActivity? = null
        val prefs = targetContext.getSharedPreferences("naiwa_updates", Context.MODE_PRIVATE)
        val testKeys = listOf("initial_check_started", "automatic", "notifications", "last_check", "ignored_version", "notified_version", "snoozed_version", "snooze_until", "cached_release", "status_kind", "status_reason")
        val oldValues = testKeys.associateWith { prefs.all[it] }
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "Update fixtures are restricted to an isolated emulator"
            }
            prefs.edit().putLong("last_check", maxOf(1, prefs.getLong("last_check", 0)))
                .putBoolean("automatic", false).putBoolean("notifications", false).commit()
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            val manager = UpdateManager.get(targetContext)
            val originalState = manager.state
            val originalRelease = manager.release
            val originalDownload = manager.downloadState
            val ui = field<CompanionUi>(screen, "ui")
            val coordinator = field<UpdateCoordinator>(manager, "coordinator")
            val sourceField = coordinator.javaClass.getDeclaredField("source").apply { isAccessible = true }
            val originalSource = sourceField.get(coordinator)
            val transportField = manager.javaClass.getDeclaredField("transport").apply { isAccessible = true }
            val originalTransport = transportField.get(manager)
            val sample = UpdateRelease(ReleaseManifest("vTEST-7", "测试版 v0.7.0", 7, targetContext.packageName,
                "naiwa.apk", 1_048_576, "a".repeat(64), "测试数据：新增动作与细节调整。"),
                ReleaseAsset("naiwa.apk", 1_048_576, "https://github.com/123wudi908/NaiwaPet/releases/download/vTEST-7/naiwa.apk"),
                "2026-10-27T00:00:00Z", byteArrayOf(), byteArrayOf())
            fun render(page: AppPage, result: CheckResult, release: UpdateRelease? = null, download: DownloadState = DownloadState.Idle) {
                runOnMainSync {
                    set(manager, "state", result); set(manager, "release", release); set(manager, "downloadState", download)
                    ui.show(page)
                }
                waitForIdleSync(); SystemClock.sleep(350)
            }
            fun checkText(label: String) { check(allText(screen.window.decorView).any { it.contains(label) }) { "Missing UI: $label" } }
            fun capture(name: String, bottom: Boolean = false) {
                if (bottom) runOnMainSync { field<ScrollView>(ui, "scroll").fullScroll(View.FOCUS_DOWN) }
                waitForIdleSync(); SystemClock.sleep(450)
                val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val directory = File(targetContext.getExternalFilesDir(null), "update-ui-test").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun runCase(name: String, block: () -> Unit) {
                try { block(); passed++; sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") }) }
                catch (error: Throwable) { failures.add("$name: ${error.stackTraceToString()}") }
            }
            runCase("replaceable source drives real check states") {
                for (result in listOf(CheckResult.Unavailable("测试无 Release"), CheckResult.Failure("测试断网"),
                        CheckResult.UpToDate, CheckResult.Available(sample))) {
                    sourceField.set(coordinator, UpdateSource { result })
                    manager.requestCheck()
                    val deadline = SystemClock.uptimeMillis() + 8000
                    while (SystemClock.uptimeMillis() < deadline &&
                        (manager.state != result || field<AtomicBoolean>(manager, "checkBusy").get())) SystemClock.sleep(30)
                    check(manager.state == result) { "Fixture result was not presented: $result" }
                }
                sourceField.set(coordinator, originalSource)
            }
            runCase("settings states and retry") {
                render(AppPage.SETTINGS, CheckResult.Checking); checkText("正在检查更新")
                render(AppPage.SETTINGS, CheckResult.UpToDate); checkText("已经是最新版本啦")
                render(AppPage.SETTINGS, CheckResult.Unavailable("尚未发布正式 Release")); checkText("尚未发布正式 Release"); capture("settings-unavailable")
                render(AppPage.SETTINGS, CheckResult.Failure("测试网络断开")); checkText("测试网络断开"); checkText("重试检查")
            }
            runCase("home card, ignore and manual details") {
                prefs.edit().putLong("ignored_version", 0).remove("snoozed_version").remove("snooze_until").commit()
                render(AppPage.HOME, CheckResult.Available(sample), sample); checkText("立即更新"); checkText("稍后提醒"); capture("home-available")
                runOnMainSync { checkNotNull(findTextView(screen.window.decorView, "稍后提醒")).performClick(); ui.refreshUpdate() }
                check(manager.snoozedVersion == 7L && manager.snoozeUntil > System.currentTimeMillis())
                check(allText(screen.window.decorView).none { it.contains("立即更新") })
                runOnMainSync { manager.restoreReminders(); ui.refreshUpdate() }
                runOnMainSync { manager.ignoreCurrent(); ui.refreshUpdate() }
                check(allText(screen.window.decorView).none { it.contains("立即更新") })
                render(AppPage.SETTINGS, CheckResult.Available(sample), sample); checkText("查看更新详情"); capture("settings-available")
            }
            runCase("download progress, cancellation, retry and ready") {
                render(AppPage.UPDATE, CheckResult.Available(sample), sample, DownloadState.Downloading(524_288, 1_048_576))
                checkText("50%"); checkText("取消下载"); capture("details-downloading", true)
                render(AppPage.UPDATE, CheckResult.Available(sample), sample, DownloadState.Cancelled); checkText("已取消下载"); checkText("重新下载")
                render(AppPage.UPDATE, CheckResult.Available(sample), sample, DownloadState.Failed("测试文件损坏")); checkText("测试文件损坏"); checkText("重试下载"); capture("details-failed", true)
                render(AppPage.UPDATE, CheckResult.Available(sample), sample, DownloadState.Ready); checkText("安装更新"); capture("details-ready", true)
                val invalid = File(targetContext.cacheDir, "invalid-update.apk").apply { writeText("invalid") }
                try { check(ApkIdentity.validate(targetContext, invalid, sample) != null) { "Invalid APK accepted" } } finally { invalid.delete() }
            }
            runCase("new release cannot reuse old ready state") {
                val newer = sample.copy(manifest = sample.manifest.copy(versionName = "测试版 v0.8.0", versionCode = 8, sha256 = "b".repeat(64)))
                render(AppPage.UPDATE, CheckResult.Available(sample), sample, DownloadState.Ready)
                sourceField.set(coordinator, UpdateSource { CheckResult.Available(newer) })
                manager.requestCheck()
                val deadline = SystemClock.uptimeMillis() + 8000
                while (SystemClock.uptimeMillis() < deadline &&
                    (manager.release?.manifest?.versionCode != 8L || field<AtomicBoolean>(manager, "checkBusy").get())) SystemClock.sleep(30)
                check(manager.release?.manifest?.versionCode == 8L)
                check(manager.downloadState == DownloadState.Idle) { "An old APK was presented as ready for a new release" }
                sourceField.set(coordinator, originalSource)
            }
            runCase("signed fixture download, cancellation, corruption and signer verification") {
                val directory = File(targetContext.getExternalFilesDir(null), "update-fixtures")
                val goodApk = File(directory, "naiwa.apk")
                val goodJson = File(directory, "naiwa-update.json")
                val goodSig = File(directory, "naiwa-update.sig")
                val badApk = File(directory, "bad-signer.apk")
                val badJson = File(directory, "bad-signer.json")
                val badSig = File(directory, "bad-signer.sig")
                check(listOf(goodApk, goodJson, goodSig, badApk, badJson, badSig).all(File::isFile)) { "Signed APK fixtures are missing" }
                class FixtureTransport : UpdateTransport {
                    var apk = goodApk
                    var metadata = goodJson
                    var signature = goodSig
                    var slow = false
                    var corrupt = false
                    val opens = AtomicInteger()
                    override fun get(url: String, maxBytes: Int): HttpReply {
                        val manifest = GitHubReleaseSource.parseManifest(metadata.readBytes())
                        val base = "https://github.com/123wudi908/NaiwaPet/releases/download/${manifest.tag}/"
                        val assets = JSONArray().put(JSONObject().put("name", "naiwa-update.json").put("size", metadata.length()).put("browser_download_url", base + "naiwa-update.json"))
                            .put(JSONObject().put("name", "naiwa-update.sig").put("size", signature.length()).put("browser_download_url", base + "naiwa-update.sig"))
                            .put(JSONObject().put("name", apk.name).put("size", apk.length()).put("browser_download_url", base + apk.name))
                        return when (url) {
                            GitHubReleaseSource.API + "/releases/latest" -> HttpReply(200, JSONObject().put("draft", false).put("prerelease", false)
                                .put("tag_name", manifest.tag).put("published_at", "2026-10-27T00:00:00Z").put("assets", assets).toString().toByteArray())
                            base + "naiwa-update.json" -> HttpReply(200, metadata.readBytes())
                            base + "naiwa-update.sig" -> HttpReply(200, signature.readBytes())
                            else -> error("Unexpected fixture URL")
                        }
                    }
                    override fun openDownload(url: String): InputStream {
                        opens.incrementAndGet()
                        return object : FilterInputStream(FileInputStream(apk)) {
                            private var first = true
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (slow) SystemClock.sleep(10)
                                val count = super.read(buffer, offset, if (slow) minOf(length, 4096) else length)
                                if (corrupt && first && count > 0) { buffer[offset] = (buffer[offset].toInt() xor 1).toByte(); first = false }
                                return count
                            }
                        }
                    }
                }
                fun awaitState(description: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 30_000
                    while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(40)
                    check(condition()) { "Timed out: $description; state=${manager.downloadState}" }
                }
                val fixture = FixtureTransport()
                val installed = ApkIdentity.installedInfo(targetContext)
                val signer = checkNotNull(ApkIdentity.publicKey(installed))
                fun checkFixture() {
                    sourceField.set(coordinator, GitHubReleaseSource(fixture, signer, targetContext.packageName, ApkIdentity.version(installed)))
                    manager.requestCheck()
                    awaitState("fixture check") { manager.state is CheckResult.Available && !field<AtomicBoolean>(manager, "checkBusy").get() }
                }
                try {
                    transportField.set(manager, fixture)
                    manager.apkFile.delete()
                    checkFixture()
                    fixture.slow = true
                    manager.download(); manager.download()
                    awaitState("download started") { fixture.opens.get() == 1 }
                    manager.cancelDownload()
                    awaitState("download cancelled") { manager.downloadState == DownloadState.Cancelled && !field<AtomicBoolean>(manager, "downloadBusy").get() }
                    check(!manager.apkFile.exists())
                    fixture.slow = false; fixture.corrupt = true
                    manager.download()
                    awaitState("corrupt download rejected") { manager.downloadState is DownloadState.Failed && !field<AtomicBoolean>(manager, "downloadBusy").get() }
                    check(!manager.apkFile.exists())
                    fixture.corrupt = false
                    manager.download(); manager.download()
                    awaitState("verified download") { manager.downloadState == DownloadState.Ready && !field<AtomicBoolean>(manager, "downloadBusy").get() }
                    check(fixture.opens.get() == 3) { "Concurrent requests downloaded more than once" }
                    manager.download()
                    awaitState("verified file reused") { manager.downloadState == DownloadState.Ready && !field<AtomicBoolean>(manager, "downloadBusy").get() }
                    check(fixture.opens.get() == 3) { "Existing verified file was downloaded again" }
                    val completed = CountDownLatch(1)
                    var error: String? = "not completed"
                    manager.validateForInstall { error = it; completed.countDown() }
                    check(completed.await(20, TimeUnit.SECONDS) && error == null) { "Valid APK rejected: $error" }
                    val uri = Uri.parse("content://${targetContext.packageName}.updates/update.apk")
                    targetContext.contentResolver.openFileDescriptor(uri, "r").use { check(it != null) }
                    fun invokeInstall() = runOnMainSync {
                        screen.javaClass.getDeclaredMethod("installUpdate").apply { isAccessible = true }.invoke(screen)
                    }
                    fun waitForSystemWindow(): AccessibilityNodeInfo {
                        val deadline = SystemClock.uptimeMillis() + 15_000
                        while (SystemClock.uptimeMillis() < deadline) {
                            val root = uiAutomation.rootInActiveWindow
                            if (root != null && root.packageName?.toString() != targetContext.packageName) return root
                            SystemClock.sleep(100)
                        }
                        error("System permission or installer window did not appear")
                    }
                    render(AppPage.UPDATE, CheckResult.Available(checkNotNull(manager.release)), manager.release, DownloadState.Ready)
                    check(!targetContext.packageManager.canRequestPackageInstalls()) { "Reset unknown-source permission before the probe" }
                    invokeInstall()
                    val settingsRoot = waitForSystemWindow()
                    check(settingsRoot.packageName.toString().contains("settings")) { "Unknown-source settings did not open" }
                    capture("unknown-source-permission")
                    val candidates = settingsRoot.findAccessibilityNodeInfosByText("Allow from this source") +
                        settingsRoot.findAccessibilityNodeInfosByText("允许来自此来源")
                    var toggle = candidates.firstOrNull() ?: error("Unknown-source permission switch was not found")
                    while (!toggle.isClickable && toggle.parent != null) toggle = toggle.parent
                    check(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not enable test permission" }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitState("unknown-source permission granted") { targetContext.packageManager.canRequestPackageInstalls() }
                    awaitState("return to app") { uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName && field<Boolean>(screen, "resumed") }
                    waitForIdleSync(); SystemClock.sleep(500)
                    check(manager.installFeedback?.contains("返回后再点击安装") == true)
                    invokeInstall()
                    val installerRoot = waitForSystemWindow()
                    check(installerRoot.packageName.toString().contains("packageinstaller")) { "System installer did not open: ${installerRoot.packageName}" }
                    capture("system-installer-confirmation")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitState("installer cancellation") { manager.installFeedback?.contains("已取消") == true }
                    fixture.apk = badApk; fixture.metadata = badJson; fixture.signature = badSig
                    checkFixture()
                    manager.download()
                    awaitState("mismatched signer rejected") { manager.downloadState is DownloadState.Failed && !field<AtomicBoolean>(manager, "downloadBusy").get() }
                    check((manager.downloadState as DownloadState.Failed).reason.contains("签名"))
                    check(!manager.apkFile.exists())
                } finally {
                    manager.apkFile.delete(); sourceField.set(coordinator, originalSource); transportField.set(manager, originalTransport)
                }
            }
            runCase("background schedule is periodic and network constrained") {
                val scheduler = targetContext.getSystemService(JobScheduler::class.java)
                UpdateJobs.configure(targetContext, true)
                val job = scheduler.allPendingJobs.single { it.service.className.endsWith("UpdateJobService") }
                check(job.isPeriodic && job.intervalMillis >= 24 * 60 * 60 * 1000L && job.networkType == JobInfo.NETWORK_TYPE_ANY && job.isPersisted)
                UpdateJobs.configure(targetContext, false)
                check(scheduler.allPendingJobs.none { it.service.className.endsWith("UpdateJobService") })
            }
            runCase("notification respects consent, deduplicates and opens details") {
                render(AppPage.HOME, CheckResult.Available(sample), sample)
                check(UpdateNotifications.permitted(targetContext)) { "Grant test notification permission before this probe" }
                val notifications = targetContext.getSystemService(NotificationManager::class.java)
                val id = 0x4e58
                fun waitForNotification(present: Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (SystemClock.uptimeMillis() < deadline && notifications.activeNotifications.any { it.id == id } != present)
                        SystemClock.sleep(50)
                    check(notifications.activeNotifications.any { it.id == id } == present)
                }
                notifications.cancel(id)
                waitForNotification(false)
                val store = object : UpdateStore {
                    override var lastCheck = 0L
                    override var ignoredVersion = 0L
                    override var notifiedVersion = 0L
                }
                UpdateNotifications.notifyIfAllowed(targetContext, sample, false, false, store)
                check(notifications.activeNotifications.none { it.id == id })
                UpdateNotifications.notifyIfAllowed(targetContext, sample, true, true, store)
                check(notifications.activeNotifications.none { it.id == id })
                UpdateNotifications.notifyIfAllowed(targetContext, sample, true, false, store)
                check(store.notifiedVersion == 7L)
                waitForNotification(true)
                val posted = notifications.activeNotifications.singleOrNull { it.id == id }
                    ?: error("System did not publish the update notification")
                posted.notification.contentIntent.send()
                waitForIdleSync(); SystemClock.sleep(400)
                check(ui.page == AppPage.UPDATE) { "Notification did not open update details" }
                notifications.cancel(id)
                waitForNotification(false)
                UpdateNotifications.notifyIfAllowed(targetContext, sample, true, false, store)
                SystemClock.sleep(500)
                check(notifications.activeNotifications.none { it.id == id })
                store.notifiedVersion = 0
                UpdateNotifications.notifyIfAllowed(targetContext, sample, false, false, store)
                SystemClock.sleep(500)
                check(notifications.activeNotifications.none { it.id == id })
                UpdateNotifications.notifyIfAllowed(targetContext, sample, true, false, store)
                waitForNotification(true)
                manager.setNotifications(false)
                waitForNotification(false)
            }
            runOnMainSync { set(manager, "state", originalState); set(manager, "release", originalRelease); set(manager, "downloadState", originalDownload); screen.finish() }
            sourceField.set(coordinator, originalSource)
        } catch (error: Throwable) { failures.add(error.stackTraceToString()); activity?.finish() }
        finally {
            val edit = prefs.edit()
            oldValues.forEach { (key, value) -> when (value) {
                null -> edit.remove(key); is Boolean -> edit.putBoolean(key, value); is Long -> edit.putLong(key, value)
                is String -> edit.putString(key, value); is Int -> edit.putInt(key, value)
            } }
            edit.commit()
        }
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\nUpdate UI probes: $passed passed, ${failures.size} failed\n" + failures.joinToString("\n"))
        })
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun allText(view: View): List<String> {
        val result = mutableListOf<String>()
        if (view is TextView) result.add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) result.addAll(allText(view.getChildAt(index)))
        return result
    }
    private fun findTextView(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTextView(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }
}
