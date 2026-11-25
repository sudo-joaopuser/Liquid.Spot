package app.liquidspot.xposed

import android.content.SharedPreferences
import app.liquidspot.core.FeatureInfo

class ModulePrefs(val prefs: SharedPreferences?) {
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun isEnabled(feature: FeatureInfo): Boolean =
        prefs?.getBoolean(feature.prefKey, feature.defaultEnabled) ?: feature.defaultEnabled

    fun getBoolean(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default

    fun getString(key: String, default: String?): String? = prefs?.getString(key, default) ?: default

    fun getInt(key: String, default: Int): Int = prefs?.getInt(key, default) ?: default

    fun getLong(key: String, default: Long): Long = prefs?.getLong(key, default) ?: default

    fun onChange(key: String, block: () -> Unit) {
        val p = prefs ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) block()
        }
        listeners += listener
        p.registerOnSharedPreferenceChangeListener(listener)
    }
}
