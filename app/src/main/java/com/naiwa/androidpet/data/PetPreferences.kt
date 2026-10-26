package com.naiwa.androidpet.data

import android.content.Context
import android.content.SharedPreferences
import com.naiwa.androidpet.core.OverlayStore
import com.naiwa.androidpet.core.Position

class PetPreferences(context: Context) : OverlayStore {
    private val prefs = context.getSharedPreferences("naiwa_settings", Context.MODE_PRIVATE)
    var wantedRunning: Boolean
        get() = prefs.getBoolean("wanted_running", false)
        @android.annotation.SuppressLint("ApplySharedPref") // Persist explicit stop before process reclamation.
        set(value) { prefs.edit().putBoolean("wanted_running", value).commit() }
    var lastDiagnostic: String?
        get() = prefs.getString("last_diagnostic", null)
        set(value) { prefs.edit().putString("last_diagnostic", value?.take(1024)).apply() }
    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound",false)
        set(value) { prefs.edit().putBoolean("sound",value).apply() }
    var animationsEnabled: Boolean
        get() = prefs.getBoolean("animations",true)
        set(value) { prefs.edit().putBoolean("animations",value).apply() }
    var feedbackEnabled: Boolean
        get() = prefs.getBoolean("feedback",true)
        set(value) { prefs.edit().putBoolean("feedback",value).apply() }
    override var position: Position?
        get() = if (prefs.contains("x") && prefs.contains("y")) Position(prefs.getInt("x", 0), prefs.getInt("y", 0)) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("x").remove("y") else edit.putInt("x", value.x).putInt("y", value.y)
            edit.apply()
        }
    override var sizeDp: Int
        get() = prefs.getInt("size_dp", 120).coerceIn(80, 220)
        set(value) { prefs.edit().putInt("size_dp", value.coerceIn(80, 220)).apply() }

    fun automaticBehaviorEnabled(defaultValue: Boolean): Boolean = prefs.getBoolean("auto_behavior",defaultValue)
    fun automaticMovementEnabled(defaultValue: Boolean): Boolean = prefs.getBoolean("auto_movement",defaultValue)
    fun setAutomaticBehavior(enabled: Boolean) { prefs.edit().putBoolean("auto_behavior",enabled).apply() }
    fun setAutomaticMovement(enabled: Boolean) { prefs.edit().putBoolean("auto_movement",enabled).apply() }

    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
