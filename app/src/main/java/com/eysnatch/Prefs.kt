package com.eysnatch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.eysnatch.core.Calibration
import com.eysnatch.core.Defaults
import com.eysnatch.core.EngineConfig
import com.eysnatch.core.Gesture
import com.eysnatch.core.PhoneAction

class EySnatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

/** All user settings. Every change bumps [version] so the running tracker reloads them. */
object Prefs {
    private lateinit var p: SharedPreferences

    @Volatile
    var version = 0
        private set

    fun init(app: Application) {
        p = app.getSharedPreferences("eysnatch", Context.MODE_PRIVATE)
    }

    private fun changed() {
        version += 1
    }

    fun action(g: Gesture): PhoneAction {
        val saved = p.getString("map_${g.name}", null)
        return PhoneAction.values().firstOrNull { it.name == saved } ?: Defaults.mapping.getValue(g)
    }

    fun setAction(g: Gesture, a: PhoneAction) {
        p.edit().putString("map_${g.name}", a.name).apply()
        changed()
    }

    fun resetMapping() {
        val e = p.edit()
        Gesture.values().forEach { e.remove("map_${it.name}") }
        e.apply()
        changed()
    }

    var sensitivity: Float
        get() = p.getFloat("sensitivity", 1f)
        set(v) { p.edit().putFloat("sensitivity", v).apply(); changed() }

    var holdScale: Float
        get() = p.getFloat("holdScale", 1f)
        set(v) { p.edit().putFloat("holdScale", v).apply(); changed() }

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) { p.edit().putBoolean("vibrate", v).apply(); changed() }

    var hud: Boolean
        get() = p.getBoolean("hud", true)
        set(v) { p.edit().putBoolean("hud", v).apply(); changed() }

    var keepAwake: Boolean
        get() = p.getBoolean("keepAwake", true)
        set(v) { p.edit().putBoolean("keepAwake", v).apply(); changed() }

    var batterySaver: Boolean
        get() = p.getBoolean("batterySaver", false)
        set(v) { p.edit().putBoolean("batterySaver", v).apply(); changed() }

    fun calibration(): Calibration {
        val m = HashMap<String, Float>()
        for (k in Calibration().toMap().keys) {
            if (p.contains("cal_$k")) m[k] = p.getFloat("cal_$k", 0f)
        }
        return Calibration.fromMap(m)
    }

    fun saveCalibration(c: Calibration) {
        val e = p.edit()
        for ((k, v) in c.toMap()) e.putFloat("cal_$k", v)
        e.apply()
        changed()
    }

    fun resetCalibration() {
        val e = p.edit()
        for (k in Calibration().toMap().keys) e.remove("cal_$k")
        e.apply()
        changed()
    }

    fun engineConfig(): EngineConfig = EngineConfig(
        sensitivity = sensitivity,
        holdScale = holdScale,
        enabled = Gesture.values().filter { action(it) != PhoneAction.NONE }.toSet(),
    )
}
