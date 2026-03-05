package com.commutebuddy.app

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class ActivePeriod(val start: Long, val end: Long)

data class MtaAlert(
    val headerText: String,
    val descriptionText: String?,
    val routeIds: Set<String>,
    val alertType: String?,
    val activePeriods: List<ActivePeriod> = emptyList(),
    val createdAt: Long? = null
)

val MONITORED_ROUTES = setOf("N", "W", "4", "5", "6", "R", "7")

object MtaAlertParser {

    fun parseAlerts(jsonString: String): List<MtaAlert> {
        return try {
            val root = JSONObject(jsonString)
            val entities = root.optJSONArray("entity") ?: return emptyList()
            val alerts = mutableListOf<MtaAlert>()
            for (i in 0 until entities.length()) {
                parseEntity(entities.optJSONObject(i))?.let { alerts.add(it) }
            }
            alerts
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEntity(entity: JSONObject?): MtaAlert? {
        if (entity == null) return null
        return try {
            val alert = entity.optJSONObject("alert") ?: return null

            val headerText = extractEnTranslation(alert.optJSONObject("header_text")) ?: return null
            val descriptionText = extractEnTranslation(alert.optJSONObject("description_text"))

            val routeIds = extractRouteIds(alert.optJSONArray("informed_entity"))

            val mercuryAlert = alert.optJSONObject("transit_realtime.mercury_alert")
            val alertType = mercuryAlert?.optString("alert_type")?.takeIf { it.isNotEmpty() }
            val createdAt = mercuryAlert?.optLong("created_at", -1L)?.takeIf { it > 0 }

            val activePeriods = parseActivePeriods(alert.optJSONArray("active_period"))

            MtaAlert(
                headerText = headerText,
                descriptionText = descriptionText,
                routeIds = routeIds,
                alertType = alertType,
                activePeriods = activePeriods,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEnTranslation(textObj: JSONObject?): String? {
        val translations = textObj?.optJSONArray("translation") ?: return null
        for (i in 0 until translations.length()) {
            val translation = translations.optJSONObject(i) ?: continue
            if (translation.optString("language") == "en") {
                val text = translation.optString("text")
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    private fun extractRouteIds(informedEntities: JSONArray?): Set<String> {
        if (informedEntities == null) return emptySet()
        val routeIds = mutableSetOf<String>()
        for (i in 0 until informedEntities.length()) {
            val entity = informedEntities.optJSONObject(i) ?: continue
            val routeId = entity.optString("route_id").takeIf { it.isNotEmpty() } ?: continue
            routeIds.add(routeId)
        }
        return routeIds
    }

    private fun parseActivePeriods(periodsArray: JSONArray?): List<ActivePeriod> {
        if (periodsArray == null) return emptyList()
        val result = mutableListOf<ActivePeriod>()
        for (i in 0 until periodsArray.length()) {
            val period = periodsArray.optJSONObject(i) ?: continue
            val start = period.optLong("start", 0L)
            val end = period.optLong("end", 0L)
            result.add(ActivePeriod(start = start, end = end))
        }
        return result
    }

    fun filterByRoutes(alerts: List<MtaAlert>, routes: Set<String>): List<MtaAlert> {
        return alerts.filter { alert -> alert.routeIds.any { it in routes } }
    }

    fun filterByActivePeriod(alerts: List<MtaAlert>, nowSeconds: Long): List<MtaAlert> {
        return alerts.filter { alert ->
            if (alert.activePeriods.isEmpty()) return@filter true
            alert.activePeriods.any { period ->
                (period.start == 0L || nowSeconds >= period.start) &&
                (period.end == 0L || nowSeconds <= period.end)
            }
        }
    }

    /**
     * Builds the structured user prompt text for the Gemini decision engine.
     *
     * @param alerts   Active alerts to include (already filtered by route and active period).
     * @param direction Commute direction string, e.g. "TO_WORK" or "TO_HOME".
     * @param nowSeconds Current time as Unix epoch seconds (used for the "Current time" header).
     */
    fun buildPromptText(alerts: List<MtaAlert>, direction: String, nowSeconds: Long): String {
        val currentTimeIso = Instant.ofEpochSecond(nowSeconds).toString()
        val sb = StringBuilder()
        sb.appendLine("Current time: $currentTimeIso")
        sb.appendLine("Direction: $direction")
        sb.appendLine()
        sb.appendLine("ALERTS:")

        if (alerts.isEmpty()) {
            sb.append("No active alerts for any monitored lines.")
            return sb.toString()
        }

        for (alert in alerts) {
            sb.appendLine("---")
            sb.appendLine("Routes: ${alert.routeIds.sorted().joinToString(",")}")
            sb.appendLine("Type: ${alert.alertType ?: "Unknown"}")
            sb.appendLine("Posted: ${alert.createdAt?.let { Instant.ofEpochSecond(it).toString() } ?: "unknown"}")
            sb.appendLine("Active period: ${formatActivePeriod(alert.activePeriods)}")
            sb.appendLine("Header: ${alert.headerText}")
            sb.appendLine("Description: ${alert.descriptionText ?: "none"}")
            sb.appendLine("---")
        }

        return sb.toString().trimEnd()
    }

    private fun formatActivePeriod(periods: List<ActivePeriod>): String {
        if (periods.isEmpty()) return "not specified"
        return periods.joinToString("; ") { period ->
            val startStr = if (period.start > 0) Instant.ofEpochSecond(period.start).toString() else "unknown"
            val endStr = if (period.end > 0) Instant.ofEpochSecond(period.end).toString() else "(open)"
            "$startStr \u2014 $endStr"
        }
    }
}
