package com.commutebuddy.app

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

class CommuteProfileRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "CommuteProfileRepository"
        private const val KEY_PROFILE = "commute_profile"
    }

    fun save(profile: CommuteProfile) {
        prefs.edit().putString(KEY_PROFILE, profile.toJson().toString()).apply()
    }

    fun load(): CommuteProfile {
        val json = prefs.getString(KEY_PROFILE, null) ?: return CommuteProfile.default()
        return try {
            CommuteProfile.fromJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved profile, using default", e)
            CommuteProfile.default()
        }
    }
}
