package com.naiwa.androidpet

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import com.naiwa.androidpet.core.*
import com.naiwa.androidpet.data.*
import com.naiwa.androidpet.runtime.*
import com.naiwa.androidpet.ui.CompanionUi
import com.naiwa.androidpet.ui.AppPage
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Permission/start/export adapter. Screen content and state rendering live in CompanionUi. */
class MainActivity : Activity() {
    private lateinit var preferences: PetPreferences
    private lateinit var ui: CompanionUi
    private lateinit var memory: PetStateRepository
    private val handler=Handler(Looper.getMainLooper())
    private val screenKey="main-${System.identityHashCode(this)}"
    private var resumed=false
    private var pendingStart=false
    private var awaitingOverlay=false
    private var awaitingNotification=false
    private var notificationStepDone=false
    private var pendingExport: String?=null
    private var backCallback:OnBackInvokedCallback?=null
    private val statusListener: () -> Unit = { if(::ui.isInitialized)ui.refresh() }
    private val preferenceListener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> statusListener() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PetDiagnostics.initialize(this);preferences=PetPreferences(this);memory=PetStateRepository.get(this)
        pendingStart=savedInstanceState?.getBoolean("pendingStart") ?: false
        awaitingOverlay=savedInstanceState?.getBoolean("awaitingOverlay") ?: false
        awaitingNotification=savedInstanceState?.getBoolean("awaitingNotification") ?: false
        notificationStepDone=savedInstanceState?.getBoolean("notificationStepDone") ?: false
        pendingExport=savedInstanceState?.getString("pendingExport")
        ui=CompanionUi(this,preferences,
            startPet={pendingStart=true;notificationStepDone=false;continueStart()},
            stopPet={
                pendingStart=false;awaitingOverlay=false;awaitingNotification=false
                preferences.wantedRunning=false
                PetDiagnostics.event("service.stop_requested","source=home")
                stopService(Intent(this,PetOverlayService::class.java));ui.refresh()
            },
            controls={
                moveTaskToBack(true)
                handler.postDelayed({
                    if(PetRuntime.running)try {
                        startService(Intent(this,PetOverlayService::class.java).setAction(PetOverlayService.ACTION_SHOW_CONTROLS))
                    }catch(error:RuntimeException){report(getString(R.string.start_failed,error.javaClass.simpleName))}
                },500)
            },
            overlaySettings={openOverlaySettings(false)},
            notificationSettings={openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))},
            export=::exportDocument,initialPage=savedInstanceState?.getString("page"))
        if(Build.VERSION.SDK_INT>=33){
            backCallback=OnBackInvokedCallback{goBack()}
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,checkNotNull(backCallback))
        }
    }
    override fun onStart() {
        super.onStart();PetRuntime.subscribe(statusListener);memory.subscribe(statusListener);preferences.listen(preferenceListener)
        PetRuntime.screenVisible(screenKey,true)
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        AppPage.entries.firstOrNull{it.name==intent.getStringExtra("page")}?.let(ui::show)
    }
    override fun onStop() {
        PetRuntime.screenVisible(screenKey,false);PetRuntime.unsubscribe(statusListener);memory.unsubscribe(statusListener)
        preferences.unlisten(preferenceListener);super.onStop()
    }
    override fun onPause(){resumed=false;ui.setActive(false);super.onPause()}
    override fun onResume(){
        super.onResume();resumed=true;ui.setActive(true)
        if(!Settings.canDrawOverlays(this) && preferences.wantedRunning){
            preferences.wantedRunning=false;report(getString(R.string.overlay_revoked_message))
        }
        if(awaitingOverlay){
            awaitingOverlay=false
            if(!Settings.canDrawOverlays(this)){pendingStart=false;report(getString(R.string.overlay_denied_message))}
        }
        if(pendingStart && !awaitingNotification)continueStart()
    }
    private fun continueStart(){
        when(PermissionPolicy.decide(Settings.canDrawOverlays(this),resumed)){
            StartDecision.WAIT_FOR_FOREGROUND->return
            StartDecision.REQUEST_OVERLAY->{openOverlaySettings(true);return}
            StartDecision.START->Unit
        }
        if(Build.VERSION.SDK_INT>=33 && !notificationStepDone &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            notificationStepDone=true;awaitingNotification=true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),NOTIFICATION_REQUEST);return
        }
        pendingStart=false
        try{startForegroundService(Intent(this,PetOverlayService::class.java).setAction(PetOverlayService.ACTION_START))}
        catch(error:RuntimeException){report(getString(R.string.start_failed,error.message ?: error.javaClass.simpleName))}
    }
    private fun openOverlaySettings(forStart:Boolean){
        awaitingOverlay=forStart
        openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
    }
    private fun openSettings(intent:Intent){
        try{startActivity(intent)}
        catch(_:ActivityNotFoundException){pendingStart=false;awaitingOverlay=false;report(getString(R.string.settings_unavailable))}
        catch(error:SecurityException){pendingStart=false;awaitingOverlay=false;report(getString(R.string.start_failed,error.javaClass.simpleName))}
    }
    private fun report(message:String){preferences.lastDiagnostic=message;PetRuntime.update(diagnostic=message)}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==NOTIFICATION_REQUEST){awaitingNotification=false;if(pendingStart && resumed)continueStart();ui.refresh()}
    }
    private fun exportDocument(name:String,content:String){
        pendingExport=content
        try{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE);type=if(name.endsWith(".json"))"application/json" else "text/plain"
            putExtra(Intent.EXTRA_TITLE,name)
        },EXPORT_REQUEST)}catch(_:ActivityNotFoundException){pendingExport=null;report("系统没有可用的文件保存界面。")}
    }
    @Deprecated("Uses Android's document picker result for the API26 compatibility floor")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=EXPORT_REQUEST)return
        val text=pendingExport;pendingExport=null
        if(resultCode!=RESULT_OK || data?.data==null || text==null)return
        try{
            val stream=contentResolver.openOutputStream(checkNotNull(data.data)) ?: throw IllegalStateException("文件不可写")
            stream.bufferedWriter(Charsets.UTF_8).use{it.write(text)}
            Toast.makeText(this,"已保存到所选文件",Toast.LENGTH_SHORT).show()
        }catch(error:Exception){report("保存失败：${error.javaClass.simpleName}")}
    }
    override fun onSaveInstanceState(outState:Bundle){
        outState.putBoolean("pendingStart",pendingStart);outState.putBoolean("awaitingOverlay",awaitingOverlay)
        outState.putBoolean("awaitingNotification",awaitingNotification);outState.putBoolean("notificationStepDone",notificationStepDone)
        outState.putString("page",ui.page.name);outState.putString("pendingExport",pendingExport);super.onSaveInstanceState(outState)
    }
    private fun goBack(){if(ui.page!=AppPage.HOME)ui.show(AppPage.HOME) else finish()}
    @Deprecated("Legacy Android back dispatch")
    @android.annotation.SuppressLint("GestureBackNavigation") // API33+ uses the guarded platform callback; API26–32 uses this fallback.
    override fun onBackPressed(){goBack()}
    override fun onDestroy(){
        if(Build.VERSION.SDK_INT>=33)backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        handler.removeCallbacksAndMessages(null);ui.close();super.onDestroy()
    }
    companion object{private const val NOTIFICATION_REQUEST=200;private const val EXPORT_REQUEST=201}
}
