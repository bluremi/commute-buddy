package com.commutebuddy.app

import org.json.JSONObject

/**
 * Commute status payload matching the BLE schema (shared/schema.json).
 * Produced by Gemini decision engine classification of raw MTA alert text.
 */
data class CommuteStatus(
    val action: String,
    val summary: String,
    val affectedRoutes: String,
    val rerouteHint: String?,
    val timestamp: Long
) {
    companion object {
        const val ACTION_NORMAL = "NORMAL"
        const val ACTION_MINOR_DELAYS = "MINOR_DELAYS"
        const val ACTION_REROUTE = "REROUTE"
        const val ACTION_STAY_HOME = "STAY_HOME"

        val VALID_ACTIONS = setOf(ACTION_NORMAL, ACTION_MINOR_DELAYS, ACTION_REROUTE, ACTION_STAY_HOME)

        /**
         * Parses a JSON string into a CommuteStatus.
         * Expects keys: action, summary, affected_routes, timestamp; optional: reroute_hint.
         * Handles Gemini responses wrapped in markdown code fences (e.g. ```json ... ```).
         * NORMAL action allows empty affected_routes.
         * @throws IllegalArgumentException if JSON is invalid or required fields are missing/invalid
         */
        fun fromJson(json: String): CommuteStatus {
            val trimmed = json.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            val jsonStr = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
            val obj = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid JSON: ${e.message}", e)
            }

            val action = obj.optString("action")
                .takeIf { it in VALID_ACTIONS }
                ?: throw IllegalArgumentException(
                    "Missing or invalid 'action' (must be one of $VALID_ACTIONS)"
                )

            val summary = obj.optString("summary")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing or empty 'summary'")

            val affectedRoutes = obj.optString("affected_routes")
            if (action != ACTION_NORMAL && affectedRoutes.isBlank()) {
                throw IllegalArgumentException("'affected_routes' must be non-empty for action '$action'")
            }

            val rerouteHint = obj.optString("reroute_hint", "").ifBlank { null }

            val timestamp = obj.optLong("timestamp", -1L).takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Missing or invalid 'timestamp' (must be epoch seconds)")

            return CommuteStatus(
                action = action,
                summary = summary,
                affectedRoutes = affectedRoutes,
                rerouteHint = rerouteHint,
                timestamp = timestamp
            )
        }
    }

    /** Human-readable label for the action tier */
    val statusLabel: String
        get() = when (action) {
            ACTION_NORMAL -> "Normal"
            ACTION_MINOR_DELAYS -> "Minor Delays"
            ACTION_REROUTE -> "Reroute"
            ACTION_STAY_HOME -> "Stay Home"
            else -> "Unknown"
        }

    /** Converts to a Connect IQ-compatible Map for BLE transmission. Keys match shared/schema.json. */
    fun toConnectIQMap(): Map<String, Any> = buildMap {
        put("action", action)
        put("summary", summary)
        put("affected_routes", affectedRoutes)
        if (rerouteHint != null) put("reroute_hint", rerouteHint)
        put("timestamp", timestamp)
    }
}
