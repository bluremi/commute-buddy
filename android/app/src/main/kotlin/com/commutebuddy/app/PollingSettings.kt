package com.commutebuddy.app

import org.json.JSONArray
import org.json.JSONObject

data class CommuteWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    fun isActive(hourOfDay: Int, minute: Int): Boolean {
        val now = hourOfDay * 60 + minute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (start <= end) {
            now >= start && now < end
        } else {
            // window spans midnight
            now >= start || now < end
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("startHour", startHour)
        put("startMinute", startMinute)
        put("endHour", endHour)
        put("endMinute", endMinute)
    }

    companion object {
        fun fromJson(json: JSONObject) = CommuteWindow(
            startHour = json.getInt("startHour"),
            startMinute = json.getInt("startMinute"),
            endHour = json.getInt("endHour"),
            endMinute = json.getInt("endMinute")
        )
    }
}

data class PollingSettings(
    val enabled: Boolean,
    val windows: List<CommuteWindow>,
    val intervalMinutes: Int
) {
    fun toJson(): JSONObject {
        val windowsArray = JSONArray()
        windows.forEach { windowsArray.put(it.toJson()) }
        return JSONObject().apply {
            put("enabled", enabled)
            put("windows", windowsArray)
            put("intervalMinutes", intervalMinutes)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PollingSettings {
            val windowsArray = json.getJSONArray("windows")
            val windows = (0 until windowsArray.length()).map {
                CommuteWindow.fromJson(windowsArray.getJSONObject(it))
            }
            return PollingSettings(
                enabled = json.getBoolean("enabled"),
                windows = windows,
                intervalMinutes = json.getInt("intervalMinutes")
            )
        }

        fun default() = PollingSettings(
            enabled = false,
            windows = listOf(
                CommuteWindow(8, 0, 9, 30),   // 8:00–9:30 AM
                CommuteWindow(17, 30, 19, 0)  // 5:30–7:00 PM
            ),
            intervalMinutes = 5
        )
    }
}
