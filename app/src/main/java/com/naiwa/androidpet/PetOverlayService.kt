package com.naiwa.androidpet

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import android.provider.Settings
import android.util.Log
import com.naiwa.androidpet.core.OverlaySession
import com.naiwa.androidpet.core.PetSize
import com.naiwa.androidpet.data.PetPreferences
import com.naiwa.androidpet.overlay.OverlayWindowController
import com.naiwa.androidpet.runtime.PetRuntime
import com.naiwa.androidpet.runtime.PetDiagnostics

class PetOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: PetPreferences
    private var window: OverlayWindowController? = null
    private var session: OverlaySession? = null
    private var foreground = false
    private var watching = false
    private var cleaning = false
    private var appliedSize = 0
    private val runtimeListener: () -> Unit = { window?.setInApp(PetRuntime.appVisible) }
    private val opListener = AppOpsManager.OnOpChangedListener { operation, pkg ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && (pkg == null || pkg == packageName)) {
            handler.post { checkPermission() }
        }
    }
    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "size_dp") handler.post {
            val size = preferences.sizeDp
            if (session?.isOpen == true && size != appliedSize) safely {
                appliedSize = size
                session?.resize(pixelSize(size), size)
            }
        }
        if (key in setOf("auto_behavior","auto_movement","animations")) handler.post { window?.syncPreferences() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(intent.action !in setOf(Intent.ACTION_SCREEN_OFF,Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT))return
            PetDiagnostics.event("screen.event","action=${intent.action}")
            if (session?.isOpen == true) safely { updateVisibility() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = PetPreferences(this)
        PetDiagnostics.initialize(this)
        PetDiagnostics.event("service.create", "wanted=${preferences.wantedRunning}")
        PetRuntime.update(diagnostic = preferences.lastDiagnostic)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PetDiagnostics.event("service.start", "command=${intent?.action ?: "system-recreate"} open=${session?.isOpen == true}")
        if (intent?.action == ACTION_STOP) { stopPet(); return START_NOT_STICKY }
        if (intent?.action == ACTION_SHOW_CONTROLS) {
            if (session?.isOpen == true) window?.showControls() else if (!foreground) stopSelf()
            return if (session?.isOpen == true) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action != ACTION_START && !(intent == null && preferences.wantedRunning)) {
            if (!foreground) stopSelf()
            return if (session?.isOpen == true) START_STICKY else START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            PetRuntime.update(running = false, diagnostic = getString(R.string.overlay_denied_message))
            stopPet(); return START_NOT_STICKY
        }
        if (session?.isOpen == true) {
            updateVisibility()
            window?.ensureAttached()
            return START_STICKY
        }
        try {
            preferences.wantedRunning = true
            promoteToForeground()
            val controller = OverlayWindowController(this,
                currentPosition = { checkNotNull(session).position },
                onMove = { session?.moveTo(it) },
                onDragEnd = { session?.savePosition() },
                onClose = { stopPet() },
                onResize = { preferences.sizeDp = it },
                onBoundsChanged = { session?.updateArea(it) },
                onDiagnostic = { preferences.lastDiagnostic = it; PetRuntime.update(diagnostic = it) },
                onFailure = { fail(it) })
            window = controller
            if (!controller.canStart) {
                PetRuntime.update(running = false, diagnostic = controller.initialDiagnostic,
                    materialInfo = controller.materialInfo)
                stopPet(); return START_NOT_STICKY
            }
            val newSession = OverlaySession(controller, preferences)
            session = newSession
            appliedSize = preferences.sizeDp
            if (!newSession.start(controller.screenArea(), pixelSize(appliedSize), Settings.canDrawOverlays(this))) {
                stopPet(); return START_NOT_STICKY
            }
            startWatching()
            preferences.lastDiagnostic=controller.initialDiagnostic
            PetRuntime.update(running = true, diagnostic = controller.initialDiagnostic, materialInfo = controller.materialInfo)
            updateVisibility()
        } catch (error: RuntimeException) { fail(error) }
        return if (session?.isOpen == true) START_STICKY else START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        })
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(this, 2, Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP), immutable)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_pet), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun startWatching() {
        // Set before registration so partially failed setup also has a cleanup path.
        watching = true
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opListener)
        preferences.listen(sizeListener)
        PetRuntime.subscribe(runtimeListener)
        runtimeListener()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        // USER_PRESENT is sent by SystemUI's separate UID. NOT_EXPORTED rejects it and
        // strands the pet hidden after secure unlock. These three actions are platform
        // protected broadcasts, so ordinary apps cannot spoof this exported receiver.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenReceiver, filter)
    }

    private fun checkPermission() {
        if (session?.isOpen == true && !Settings.canDrawOverlays(this)) {
            PetDiagnostics.event("permission.revoked")
            preferences.lastDiagnostic = getString(R.string.overlay_revoked_message)
            PetRuntime.update(diagnostic = getString(R.string.overlay_revoked_message))
            stopPet()
        }
    }

    private fun updateVisibility() {
        checkPermission()
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        window?.setVisible(interactive && !locked)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        safely {
            window?.let { session?.resize(pixelSize(preferences.sizeDp), preferences.sizeDp); session?.updateArea(it.screenArea()) }
            window?.refreshConfiguration()
        }
    }

    override fun onTrimMemory(level: Int) { PetDiagnostics.event("memory.trim", "level=$level"); window?.trimMemory(); super.onTrimMemory(level) }

    private fun pixelSize(dp: Int): PetSize {
        val pixels = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return window?.preferredSize(pixels) ?: PetSize(pixels, pixels)
    }

    private fun safely(block: () -> Unit) { try { block() } catch (error: RuntimeException) { fail(error) } }
    private fun fail(error: RuntimeException) {
        PetDiagnostics.event("service.failed", error = error)
        preferences.lastDiagnostic = getString(R.string.window_failed, error.message ?: error.javaClass.simpleName)
        PetRuntime.update(diagnostic = preferences.lastDiagnostic)
        stopPet()
    }

    private fun stopPet() {
        preferences.wantedRunning = false
        PetDiagnostics.event("service.stop_requested")
        cleanup(); stopSelf()
    }

    private fun cleanup() {
        if (cleaning) return
        cleaning = true
        handler.removeCallbacksAndMessages(null)
        if (watching) {
            watching = false
            try { getSystemService(AppOpsManager::class.java).stopWatchingMode(opListener) } catch (error: RuntimeException) { Log.w(TAG, "Remove permission observer", error) }
            preferences.unlisten(sizeListener)
            PetRuntime.unsubscribe(runtimeListener)
            try { unregisterReceiver(screenReceiver) } catch (error: IllegalArgumentException) { Log.w(TAG, "Remove screen receiver", error) }
        }
        try { session?.close() } catch (error: RuntimeException) { Log.w(TAG, "Close session", error) }
        try { window?.close() } catch (error: RuntimeException) { Log.w(TAG, "Remove window", error) }
        session = null; window = null
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        PetRuntime.update(running = false)
        cleaning = false
    }

    override fun onDestroy() { PetDiagnostics.event("service.destroy", "wanted=${preferences.wantedRunning}"); cleanup(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { PetDiagnostics.event("activity.task_removed"); super.onTaskRemoved(rootIntent) }

    companion object {
        const val ACTION_START = "com.naiwa.androidpet.START"
        const val ACTION_STOP = "com.naiwa.androidpet.STOP"
        const val ACTION_SHOW_CONTROLS = "com.naiwa.androidpet.SHOW_CONTROLS"
        private const val CHANNEL = "naiwa_pet_running"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "NaiwaPet"
    }
}
