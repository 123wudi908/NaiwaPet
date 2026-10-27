package com.naiwa.androidpet.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.naiwa.androidpet.MainActivity
import com.naiwa.androidpet.R

object UpdateNotifications {
    private const val CHANNEL = "naiwa_updates"
    private const val ID = 0x4e58
    fun cancel(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }
    fun permitted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun notifyIfAllowed(context: Context, release: UpdateRelease, enabled: Boolean, foreground: Boolean, store: UpdateStore,
        snoozedVersion: Long = 0, snoozeUntil: Long = 0) {
        if (!UpdatePolicy.shouldNotify(NotificationDecision(enabled, permitted(context), foreground,
                release.manifest.versionCode, store.ignoredVersion, store.notifiedVersion,
                snoozedVersion, snoozeUntil, System.currentTimeMillis()))) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_DEFAULT))
        if (!permitted(context)) return
        val intent = Intent(context, MainActivity::class.java).putExtra("page", "UPDATE")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0x4e58, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pet)
            .setContentTitle("奶蛙有新版本啦！")
            .setContentText("发现 ${release.manifest.versionName}，点击查看新增功能。")
            .setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(ID, notification); store.notifiedVersion = release.manifest.versionCode }
        catch (_: SecurityException) { }
    }
}
