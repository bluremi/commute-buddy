package com.commutebuddy.app

import org.json.JSONArray
import org.json.JSONObject

data class CommuteLeg(
    val lines: List<String>,
    val direction: String,
    val fromStation: String,
    val toStation: String
) {
    fun toJson(): JSONObject {
        val linesArray = JSONArray()
        lines.forEach { linesArray.put(it) }
        return JSONObject().apply {
            put("lines", linesArray)
            put("direction", direction)
            put("fromStation", fromStation)
            put("toStation", toStation)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CommuteLeg {
            val linesArray = json.getJSONArray("lines")
            val lines = (0 until linesArray.length()).map { linesArray.getString(it) }
            return CommuteLeg(
                lines = lines,
                direction = json.getString("direction"),
                fromStation = json.getString("fromStation"),
                toStation = json.getString("toStation")
            )
        }
    }
}
