package com.commutebuddy.app

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

class PollingSettingsRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "PollingSettingsRepo"
        private const val KEY_SETTINGS = "polling_settings"
    }

    fun save(settings: PollingSettings) {
        prefs.edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()
    }

    fun load(): PollingSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return PollingSettings.default()
        return try {
            PollingSettings.fromJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse polling settings, using default", e)
            PollingSettings.default()
        }
    }
}
