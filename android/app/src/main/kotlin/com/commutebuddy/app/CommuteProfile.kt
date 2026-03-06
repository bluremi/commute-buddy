package com.commutebuddy.app

import org.json.JSONArray
import org.json.JSONObject

data class CommuteProfile(
    val toWorkLegs: List<CommuteLeg>,
    val toHomeLegs: List<CommuteLeg>,
    val alternates: List<String>
) {
    fun monitoredRoutes(): Set<String> {
        val routes = mutableSetOf<String>()
        (toWorkLegs + toHomeLegs).forEach { routes.addAll(it.lines) }
        routes.addAll(alternates)
        return routes
    }

    fun toJson(): JSONObject {
        val toWorkArray = JSONArray()
        toWorkLegs.forEach { toWorkArray.put(it.toJson()) }
        val toHomeArray = JSONArray()
        toHomeLegs.forEach { toHomeArray.put(it.toJson()) }
        val alternatesArray = JSONArray()
        alternates.forEach { alternatesArray.put(it) }
        return JSONObject().apply {
            put("toWorkLegs", toWorkArray)
            put("toHomeLegs", toHomeArray)
            put("alternates", alternatesArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CommuteProfile {
            val toWorkArray = json.getJSONArray("toWorkLegs")
            val toWorkLegs = (0 until toWorkArray.length()).map {
                CommuteLeg.fromJson(toWorkArray.getJSONObject(it))
            }
            val toHomeArray = json.getJSONArray("toHomeLegs")
            val toHomeLegs = (0 until toHomeArray.length()).map {
                CommuteLeg.fromJson(toHomeArray.getJSONObject(it))
            }
            val alternatesArray = json.getJSONArray("alternates")
            val alternates = (0 until alternatesArray.length()).map { alternatesArray.getString(it) }
            return CommuteProfile(toWorkLegs, toHomeLegs, alternates)
        }

        fun default(): CommuteProfile = CommuteProfile(
            toWorkLegs = listOf(
                CommuteLeg(listOf("N", "W"), "Manhattan-bound", "Astoria", "59th St"),
                CommuteLeg(listOf("4", "5"), "Downtown", "59th St", "14th St"),
                CommuteLeg(listOf("6"), "Downtown", "14th St", "Spring St")
            ),
            toHomeLegs = listOf(
                CommuteLeg(listOf("6"), "Uptown", "Spring St", "14th St"),
                CommuteLeg(listOf("4", "5"), "Uptown", "14th St", "59th St"),
                CommuteLeg(listOf("N", "W"), "Queens-bound", "59th St", "Astoria")
            ),
            alternates = listOf("F", "R", "7")
        )
    }
}
