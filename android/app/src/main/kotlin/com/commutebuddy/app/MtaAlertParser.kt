package com.commutebuddy.app

import org.json.JSONArray
import org.json.JSONObject

data class ActivePeriod(val start: Long, val end: Long)

data class MtaAlert(
    val headerText: String,
    val descriptionText: String?,
    val routeIds: Set<String>,
    val alertType: String?,
    val activePeriods: List<ActivePeriod> = emptyList()
)

val MONITORED_ROUTES = setOf("N", "W", "4", "5", "6")

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

            val alertType = alert.optJSONObject("transit_realtime.mercury_alert")
                ?.optString("alert_type")
                ?.takeIf { it.isNotEmpty() }

            val activePeriods = parseActivePeriods(alert.optJSONArray("active_period"))

            MtaAlert(
                headerText = headerText,
                descriptionText = descriptionText,
                routeIds = routeIds,
                alertType = alertType,
                activePeriods = activePeriods
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

    fun buildPromptText(alerts: List<MtaAlert>): String {
        return alerts.joinToString(separator = "\n\n") { alert ->
            val typeLabel = alert.alertType ?: "Alert"
            val sb = StringBuilder("--- Alert ($typeLabel) ---\n")
            sb.append(alert.headerText)
            alert.descriptionText?.let { sb.append("\n").append(it) }
            sb.toString()
        }
    }
}
