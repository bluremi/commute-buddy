package com.commutebuddy.app

import java.util.Calendar
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
    val intervalMinutes: Int,
    val activeDays: Set<Int> = DEFAULT_ACTIVE_DAYS,
    val backgroundPolling: Boolean = true
) {
    fun toJson(): JSONObject {
        val windowsArray = JSONArray()
        windows.forEach { windowsArray.put(it.toJson()) }
        val activeDaysArray = JSONArray()
        activeDays.sorted().forEach { activeDaysArray.put(it) }
        return JSONObject().apply {
            put("enabled", enabled)
            put("windows", windowsArray)
            put("intervalMinutes", intervalMinutes)
            put("activeDays", activeDaysArray)
            put("backgroundPolling", backgroundPolling)
        }
    }

    companion object {
        // Monday–Friday using java.util.Calendar day constants
        val DEFAULT_ACTIVE_DAYS: Set<Int> = setOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY
        )

        fun fromJson(json: JSONObject): PollingSettings {
            val windowsArray = json.getJSONArray("windows")
            val windows = (0 until windowsArray.length()).map {
                CommuteWindow.fromJson(windowsArray.getJSONObject(it))
            }
            val activeDays: Set<Int> = if (json.has("activeDays")) {
                val arr = json.getJSONArray("activeDays")
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            } else {
                DEFAULT_ACTIVE_DAYS
            }
            val backgroundPolling: Boolean = if (json.has("backgroundPolling")) {
                json.getBoolean("backgroundPolling")
            } else {
                true
            }
            return PollingSettings(
                enabled = json.getBoolean("enabled"),
                windows = windows,
                intervalMinutes = json.getInt("intervalMinutes"),
                activeDays = activeDays,
                backgroundPolling = backgroundPolling
            )
        }

        fun default() = PollingSettings(
            enabled = false,
            windows = listOf(
                CommuteWindow(8, 0, 9, 30),   // 8:00–9:30 AM
                CommuteWindow(17, 30, 19, 0)  // 5:30–7:00 PM
            ),
            intervalMinutes = 5,
            activeDays = DEFAULT_ACTIVE_DAYS,
            backgroundPolling = true
        )
    }
}
