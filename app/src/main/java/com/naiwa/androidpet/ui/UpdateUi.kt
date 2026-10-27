package com.naiwa.androidpet.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import com.naiwa.androidpet.update.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant

/** Update surfaces use the same native spacing, color and control system as the rest of the app. */
class UpdateUi(private val activity: Activity, private val manager: UpdateManager,
    private val details: () -> Unit, private val settings: () -> Unit,
    private val notificationToggle: (Boolean) -> Unit, private val openNotifications: () -> Unit,
    private val project: () -> Unit, private val install: () -> Unit) {
    private fun dp(value: Int) = NativeStyle.dp(activity, value)
    private fun text(parent: LinearLayout, value: String, size: Float = 14f, strong: Boolean = false) =
        NativeStyle.text(activity, value, size, strong).also { parent.addView(it) }
    private fun button(parent: LinearLayout, value: String, primary: Boolean = false, action: () -> Unit) =
        NativeStyle.button(activity, value, primary, action).also { parent.addView(it) }
    private fun card(parent: LinearLayout, color: Int = Color.WHITE): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(18))
        background = NativeStyle.surface(activity, color, NativeStyle.Radius.card)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
    }
    private fun summary(release: UpdateRelease): String = release.manifest.notes.trim().ifBlank { "这次更新没有附带说明。" }
        .take(180).let { if (release.manifest.notes.trim().length > 180) "$it…" else it }
    private fun date(value: String): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date.from(Instant.parse(value)))
    } catch (_: Exception) { value.take(32) }

    fun home(parent: LinearLayout) {
        val release = manager.release ?: return
        if (!UpdatePresentation.showHomeCard(release.manifest.versionCode, manager.ignoredVersion,
                manager.snoozedVersion, manager.snoozeUntil, System.currentTimeMillis())) return
        val panel = card(parent, NativeStyle.cream)
        panel.isClickable = true; panel.isFocusable = true; panel.setOnClickListener { details() }
        text(panel, "奶蛙有新版本啦", 18f, true)
        text(panel, release.manifest.versionName + if (release.publishedAt.isNotBlank()) " · ${date(release.publishedAt)}" else "", 13f)
        text(panel, summary(release), 14f)
        button(panel, "立即更新", true, details)
        button(panel, "稍后提醒") { manager.remindLater() }
    }

    fun settings(parent: LinearLayout) {
        val panel = card(parent)
        val installed = try { ApkIdentity.installedInfo(activity) } catch (_: Exception) { null }
        text(panel, "版本与更新", 19f, true)
        text(panel, if (installed == null) "当前版本不可读取" else "当前版本 ${installed.versionName} · versionCode ${ApkIdentity.version(installed)}", 13f)
        toggle(panel, "自动检查更新", "启动时及后台低频检查；关闭后可随时手动检查。", manager.automatic) { manager.setAutomatic(it) }
        manager.schedulingError?.let {
            text(panel, it, 13f)
            button(panel, "重试后台检查") { manager.setAutomatic(true) }
        }
        toggle(panel, "更新通知", "仅在后台发现新版本时提醒，不会自动安装。", manager.notifications, notificationToggle)
        if (manager.notifications && !UpdateNotifications.permitted(activity)) {
            text(panel, "系统通知权限未允许，开启后才能收到更新提醒。", 13f)
            button(panel, "打开通知权限设置", action = openNotifications)
        }
        val status = UpdatePresentation.message(manager.state)
        text(panel, status, 14f, manager.state is CheckResult.Available || manager.state == CheckResult.UpToDate)
            .accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (manager.state == CheckResult.Checking) panel.addView(ProgressBar(activity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(32), dp(32)))
        button(panel, if (manager.state is CheckResult.Failure) "重试检查" else "检查更新", true) { manager.requestCheck() }
            .isEnabled = manager.state != CheckResult.Checking
        text(panel, if (manager.lastCheck == 0L) "最近检查：尚未检查" else "最近检查：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(manager.lastCheck))}", 12f)
        text(panel, "GitHub：123wudi908/NaiwaPet", 12f)
        button(panel, "打开 GitHub 项目", action = project)
        manager.release?.let { release ->
            val latest = card(parent, NativeStyle.softGreen)
            text(latest, "发现 ${release.manifest.versionName}", 18f, true)
            text(latest, summary(release), 13f)
            if (release.manifest.versionCode == manager.ignoredVersion) {
                text(latest, "此版本已忽略，仍可查看详情。", 12f)
                button(latest, "恢复此版本提醒") { manager.restoreReminders() }
            } else if (release.manifest.versionCode == manager.snoozedVersion && System.currentTimeMillis() < manager.snoozeUntil) {
                text(latest, "此版本将在一天后再次提醒。", 12f)
                button(latest, "恢复此版本提醒") { manager.restoreReminders() }
            }
            button(latest, "查看更新详情", true, details)
        }
    }

    fun details(parent: LinearLayout) {
        val release = manager.release
        if (release == null) {
            val panel = card(parent)
            text(panel, UpdatePresentation.message(manager.state), 15f, true)
            button(panel, "检查更新", true) { manager.requestCheck() }
            button(panel, "返回设置", action = settings)
            return
        }
        val panel = card(parent, NativeStyle.cream)
        val installed = try { ApkIdentity.installedInfo(activity) } catch (_: Exception) { null }
        text(panel, "准备好迎接新版本", 20f, true)
        text(panel, "当前 ${installed?.versionName ?: "未知"}  →  ${release.manifest.versionName}", 15f, true)
        text(panel, "目标 versionCode ${release.manifest.versionCode}", 12f)
        if (release.publishedAt.isNotBlank()) text(panel, "发布时间 ${date(release.publishedAt)}", 12f)
        text(panel, "APK 大小 ${android.text.format.Formatter.formatShortFileSize(activity, release.manifest.size)}", 13f)
        val notes = card(parent)
        text(notes, "这次更新", 17f, true)
        text(notes, release.manifest.notes.ifBlank { "发布者没有填写更新说明。" }, 14f)
        val action = card(parent, NativeStyle.softGreen)
        when (val download = manager.downloadState) {
            DownloadState.Idle -> {
                text(action, "下载后会核对发布签名、文件摘要和安装包身份。", 13f)
                button(action, "下载更新", true) { manager.download() }
            }
            is DownloadState.Downloading -> {
                text(action, "正在下载 ${if (download.total > 0) (download.done * 100 / download.total) else 0}%", 14f, true)
                action.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = if (download.total > 0) (download.done * 100 / download.total).toInt() else 0
                    progressTintList = ColorStateList.valueOf(NativeStyle.accent); contentDescription = "更新下载进度"
                }, LinearLayout.LayoutParams(-1, dp(20)))
                button(action, "取消下载") { manager.cancelDownload() }
            }
            DownloadState.Cancelling -> text(action, "正在取消下载…", 14f)
            DownloadState.Ready -> {
                text(action, "下载完成，安装包验证通过。", 15f, true)
                text(action, "接下来由 Android 系统安装器请你确认。", 13f)
                button(action, "安装更新", true, install)
            }
            DownloadState.Cancelled -> {
                text(action, "已取消下载。", 14f)
                button(action, "重新下载", true) { manager.download() }
            }
            is DownloadState.Failed -> {
                text(action, download.reason, 14f, true)
                button(action, "重试下载", true) { manager.download() }
            }
        }
        manager.installFeedback?.let { text(action, it, 13f) }
        button(parent, "忽略此版本") { manager.ignoreCurrent(); settings() }
        button(parent, "返回设置", action = settings)
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
        parent.addView(Switch(activity).apply {
            text = title; textSize = 16f; setTextColor(NativeStyle.ink); minimumHeight = dp(48); isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(NativeStyle.accent, NativeStyle.secondary))
            setOnCheckedChangeListener { _, value -> change(value) }
        })
        text(parent, description, 12f)
    }
}
