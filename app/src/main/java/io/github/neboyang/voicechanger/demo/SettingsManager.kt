package io.github.neboyang.voicechanger.demo

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("meowrvc_settings", Context.MODE_PRIVATE)

    fun save(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
            apply()
        }
    }

    fun getInt(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun getFloat(key: String, default: Float = 0f) = prefs.getFloat(key, default)
    fun getBoolean(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun getString(key: String, default: String? = null) = prefs.getString(key, default)

    fun loadSettings() = AppSettings(
        f0UpKey = getInt("f0UpKey"),
        protectRate = getFloat("protectRate", 0.33f),
        latency = getFloat("latency", 1.0f),
        noiseLevel = getInt("noiseLevel"),
        eqLevel = getInt("eqLevel"),
        noiseGateDb = getFloat("noiseGateDb"),
        outputDenoise = getBoolean("outputDenoise"),
        vocalRangeFilter = getBoolean("vocalRangeFilter"),
        indexRate = getFloat("indexRate"),
        filterRadius = getInt("filterRadius", 3),
        backendMode = getInt("backendMode", 2),
        volume = getFloat("volume", 1.0f),
        pitchExtractor = getInt("pitchExtractor", 0),
        crossfadeSamples = getInt("crossfadeSamples", 2048),
        vadEnergyThreshold = getFloat("vadEnergyThreshold", 500f),
        vadSilenceFrames = getInt("vadSilenceFrames", 300),
        overlapDivisor = getInt("overlapDivisor", 4),
    )

    data class AppSettings(
        val f0UpKey: Int = 0,
        val protectRate: Float = 0.33f,
        val latency: Float = 1.0f,
        val noiseLevel: Int = 0,
        val eqLevel: Int = 0,
        val noiseGateDb: Float = 0f,
        val outputDenoise: Boolean = false,
        val vocalRangeFilter: Boolean = false,
        val indexRate: Float = 0f,
        val filterRadius: Int = 3,
        val backendMode: Int = 2,
        val volume: Float = 1.0f,
        val pitchExtractor: Int = 0,
        val crossfadeSamples: Int = 2048,
        val vadEnergyThreshold: Float = 500f,
        val vadSilenceFrames: Int = 300,
        val overlapDivisor: Int = 4,
    )
}
