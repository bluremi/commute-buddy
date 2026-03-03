package com.commutebuddy.app

import org.json.JSONObject

/**
 * Commute status payload matching the BLE schema (shared/schema.json).
 * Produced by on-device Gemini Nano summarization of raw MTA alert text.
 */
data class CommuteStatus(
    val status: Int,
    val routeString: String,
    val reason: String,
    val timestamp: Long
) {
    companion object {
        /** 0 = Normal (good service), 1 = Deviate (delays/planned work), 2 = Disrupted (major disruption/suspended) */
        const val STATUS_NORMAL = 0
        const val STATUS_DEVIATE = 1
        const val STATUS_ERROR = 2  // legacy name; displayed as "Disrupted"

        /**
         * Parses a JSON string into a CommuteStatus.
         * Expects keys: status, route_string, reason, timestamp
         * @throws IllegalArgumentException if JSON is invalid or required fields are missing
         */
        fun fromJson(json: String): CommuteStatus {
            val obj = try {
                JSONObject(json.trim().removeSurrounding("```json").trim().removeSurrounding("```").trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid JSON: ${e.message}", e)
            }
            return CommuteStatus(
                status = obj.optInt("status", -1).takeIf { it in 0..2 }
                    ?: throw IllegalArgumentException("Missing or invalid 'status' (must be 0, 1, or 2)"),
                routeString = obj.optString("route_string")
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing or empty 'route_string'"),
                reason = obj.optString("reason")
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing or empty 'reason'"),
                timestamp = obj.optLong("timestamp", -1L).takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("Missing or invalid 'timestamp' (must be epoch seconds)")
            )
        }
    }

    /** Human-readable status label */
    val statusLabel: String
        get() = when (status) {
            STATUS_NORMAL -> "Normal"
            STATUS_DEVIATE -> "Delays"
            STATUS_ERROR -> "Disrupted"
            else -> "Unknown"
        }
}
