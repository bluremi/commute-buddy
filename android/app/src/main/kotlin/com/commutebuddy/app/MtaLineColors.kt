package com.commutebuddy.app

import android.graphics.Color
import android.text.SpannableStringBuilder

object MtaLineColors {

    fun lineColor(line: String): Int = when (line) {
        "1", "2", "3"      -> Color.parseColor("#D82233")
        "4", "5", "6"      -> Color.parseColor("#009952")
        "7"                -> Color.parseColor("#9A38A1")
        "A", "C", "E"      -> Color.parseColor("#0062CF")
        "B", "D", "F", "M" -> Color.parseColor("#EB6800")
        "G"                -> Color.parseColor("#799534")
        "J", "Z"           -> Color.parseColor("#8E5C33")
        "L", "S"           -> Color.parseColor("#7C858C")
        "N", "Q", "R", "W" -> Color.parseColor("#F6BC26")
        else               -> Color.GRAY
    }

    // Yellow lines (#F6BC26) need black text for contrast; all others use white
    fun isLightBackground(line: String) = line in setOf("N", "Q", "R", "W")

    /**
     * Builds a [SpannableStringBuilder] with one [MtaLineBadgeSpan] per route in [routesCsv],
     * separated by single spaces. Each badge is a filled circle with the line letter centered.
     *
     * @param routesCsv comma-separated route IDs, e.g. "N,W" or "4,5,6"
     * @param textSizePx badge diameter in pixels (typically 20–24dp converted by the caller)
     */
    fun buildRouteBadges(routesCsv: String, textSizePx: Float): SpannableStringBuilder {
        val lines = routesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val ssb = SpannableStringBuilder()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) ssb.append(" ")
            val start = ssb.length
            ssb.append(line)
            ssb.setSpan(MtaLineBadgeSpan(line, textSizePx), start, ssb.length, 0)
        }
        return ssb
    }
}
