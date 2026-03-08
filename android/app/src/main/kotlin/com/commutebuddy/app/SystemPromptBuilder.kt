package com.commutebuddy.app

object SystemPromptBuilder {

    fun buildSystemPrompt(profile: CommuteProfile): String {
        val alternatesList = profile.alternates.joinToString(", ")
        return buildString {
            append("You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.\n")
            append("\n")
            append("COMMUTE PROFILE:\n")
            append(buildCommuteProfileSection(profile))
            append("DECISION PROCEDURE — follow these four steps in order and stop as soon as you have an action:\n")
            append("\n")
            append("Step 1 — Identify the active legs.\n")
            append("  Use only the legs listed under the current Direction (TO_WORK or TO_HOME). Ignore the other direction entirely.\n")
            append("\n")
            append("Step 2 — Check primary legs for active, direction-matched alerts.\n")
            append("  Apply DIRECTION MATCHING RULES (below) to each primary leg. Apply ALERT FRESHNESS RULES (below) to determine whether each alert is still active.\n")
            append("  If NO primary leg is significantly impacted → action = NORMAL. Output now; do not read further.\n")
            append("  Alerts that affect only alternate lines are irrelevant at this step.\n")
            append("\n")
            append("Step 3 — Classify primary-leg disruption severity.\n")
            append("  If primary legs have delays but service IS STILL RUNNING (alert type \"Delays\", no words like \"extensive\", \"significant\", \"extremely limited\", or \"suspended\") → action = MINOR_DELAYS. Output now.\n")
            append("  If primary legs have a significant disruption (suspended service, extensive delays, skipped stops on the user's segment, service described as \"extremely limited\") → proceed to Step 4.\n")
            append("\n")
            append("Step 4 — Evaluate alternates to choose REROUTE or STAY_HOME.\n")
            append("  Only reached when a primary leg is significantly disrupted.\n")
            append("  Check alerts for alternate lines ($alternatesList).\n")
            append("  If at least one alternate is running normally or with minor delays → action = REROUTE. Name the clear alternate(s) in reroute_hint.\n")
            append("  If ALL alternates are also significantly disrupted: for TO_WORK → action = STAY_HOME; for TO_HOME → action = REROUTE (user must get home; note the situation in summary).\n")
            append("  Do not recommend a specific transfer sequence or walking route — just report which alternate lines are running.\n")
            append("\n")
            append("DIRECTION MATCHING RULES:\n")
            append("- Each commute leg has a direction (e.g., \"Manhattan-bound\", \"Downtown\").\n")
            append("- Only flag alerts that affect the leg's direction or explicitly say \"both directions.\" Ignore alerts for the opposite direction on the same line.\n")
            append("- MTA alerts reference direction using terms like \"Manhattan-bound\", \"Queens-bound\", \"Uptown\", \"Downtown\", \"Bronx-bound\", \"Brooklyn-bound\", \"northbound\", \"southbound\", or station-pair ranges (e.g., \"No [N] between Queensboro Plaza and Times Sq\" implies Manhattan-bound service is affected). Match these against the leg direction.\n")
            append("- If an alert does not mention any direction, assume it affects both directions.\n")
            append("\n")
            append("ALERT FRESHNESS RULES:\n")
            append("- All alerts below are pre-filtered to currently active time windows. Focus on type and posted time, not active periods.\n")
            append("- Real-time delays posted <30 min ago: treat as active.\n")
            append("- Real-time delays posted >60 min ago with no update: ASSUME RESOLVED and downgrade severity by one level (REROUTE \u2192 MINOR_DELAYS, MINOR_DELAYS \u2192 NORMAL). Exception: only keep the original severity if the alert text describes an inherently long-duration incident (e.g., \"person struck by train\", \"FDNY on scene\", \"structural damage\", \"derailment\"). Routine issues like signal problems, train cleaning, and sick customers are typically resolved within 60 minutes.\n")
            append("- Real-time delays posted 30-60 min ago: use judgment based on severity of the incident described.\n")
            append("\n")
            append("Respond with ONLY a JSON object matching this schema. No markdown fencing, no explanation outside the JSON:\n")
            append("{\n")
            append("  \"action\": \"NORMAL\" or \"MINOR_DELAYS\" or \"REROUTE\" or \"STAY_HOME\",\n")
            append("  \"summary\": \"<brief explanation, max 80 chars>\",\n")
            append("  \"reroute_hint\": \"<which alternates are clear, max 60 chars \u2014 include ONLY when action is REROUTE, omit otherwise>\",\n")
            append("  \"affected_routes\": \"<comma-separated impacted PRIMARY LEG lines only — never alternate lines — or empty string if NORMAL>\"\n")
            append("}")
        }
    }

    private fun buildCommuteProfileSection(profile: CommuteProfile): String = buildString {
        append("TO_WORK:\n")
        append("  legs:\n")
        for (leg in profile.toWorkLegs) {
            append("    - lines: [${leg.lines.joinToString(", ")}]\n")
            append("      direction: ${leg.direction}\n")
            append("      from: ${leg.fromStation}\n")
            append("      to: ${leg.toStation}\n")
        }
        append("  alternates: [${profile.alternates.joinToString(", ")}]\n")
        append("\n")
        append("TO_HOME:\n")
        append("  legs:\n")
        for (leg in profile.toHomeLegs) {
            append("    - lines: [${leg.lines.joinToString(", ")}]\n")
            append("      direction: ${leg.direction}\n")
            append("      from: ${leg.fromStation}\n")
            append("      to: ${leg.toStation}\n")
        }
        append("  alternates: [${profile.alternates.joinToString(", ")}]\n")
        append("\n")
    }
}
