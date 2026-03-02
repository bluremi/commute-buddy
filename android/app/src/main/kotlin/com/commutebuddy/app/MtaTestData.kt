package com.commutebuddy.app

/**
 * Hardcoded MTA-style alert strings for the AI Summarization POC (FEAT-02).
 * Sourced from real MTA GTFS-RT feed examples in docs/mta-feed-research.md.
 */
object MtaTestData {

    enum class Tier(val labelResId: Int) {
        TIER_1(R.string.label_tier_short),
        TIER_2(R.string.label_tier_medium),
        TIER_3(R.string.label_tier_long),
        TIER_4(R.string.label_tier_stress)
    }

    /** Tier 1 (~100 chars): Real-time delay header, no description body. */
    const val TIER_1_SHORT: String =
        "[Q] trains are delayed entering and leaving 96 St while we request NYPD for someone being disruptive at that station."

    /** Tier 2 (~500 chars): Stops-skipped/reroute with transfer instructions. */
    const val TIER_2_MEDIUM: String =
        "In Queens, Jamaica Center-bound [E] skips Briarwood. For service to this station, take the [E] to Jamaica-Van Wyck and transfer to a Manhattan-bound [E]. For service from this station, take the [E] or [F] to Kew Gardens-Union Tpke and transfer to a Jamaica Center-bound [E]. What's happening? Urgent electrical repairs"

    /** Tier 3 (~900 chars): Planned suspension with shuttles, split service, ADA notice. */
    const val TIER_3_LONG: String =
        "[2] service operates in two sections: 1. Between Wakefield-241 St and 149 St-Grand Concourse 2. Between 96 St and Flatbush Av-Brooklyn College Uptown trains will skip 79 St and 86 St, take the [1] instead. [shuttle bus icon] Free shuttle buses run between 96 St and 149 St-Grand Concourse, making all [2] station stops. Transfer between [2] and [shuttle bus icon] buses at 149 St-Grand Concourse and/or 96 St [accessibility icon]. Travel alternatives: For direct service to/from midtown, take the [4] at 149 St-Grand Concourse. Transfer between [shuttle bus icon] buses and [4] at 149 St-Grand Concourse. Connect between the [2] at Times Sq-42 St and [4] at Grand Central-42 St via the [S] 42 St Shuttle or [7]. When exiting 96 St or 149 St-Grand Concourse, get a GO ticket for re-entry into the subway. What's happening? Urgent repairs. [accessibility icon] This service change affects one or more ADA accessible stations and these travel alternatives may not be fully accessible. Please contact 511 to plan your trip."

    /**
     * Tier 4 (~2000+ chars): Synthetic worst-case combining multiple long alerts.
     * Mimics weekend construction dump affecting multiple lines — tests Gemini Nano context limit.
     */
    const val TIER_4_STRESS: String =
        "[2] service operates in two sections: 1. Between Wakefield-241 St and 149 St-Grand Concourse 2. Between 96 St and Flatbush Av-Brooklyn College Uptown trains will skip 79 St and 86 St, take the [1] instead. [shuttle bus icon] Free shuttle buses run between 96 St and 149 St-Grand Concourse, making all [2] station stops. Transfer between [2] and [shuttle bus icon] buses at 149 St-Grand Concourse and/or 96 St [accessibility icon]. Travel alternatives: For direct service to/from midtown, take the [4] at 149 St-Grand Concourse. Connect between the [2] at Times Sq-42 St and [4] at Grand Central-42 St via the [S] 42 St Shuttle or [7]. When exiting 96 St or 149 St-Grand Concourse, get a GO ticket for re-entry into the subway. What's happening? Urgent repairs. [accessibility icon] This service change affects one or more ADA accessible stations. Please contact 511 to plan your trip. " +
        "In Queens, Jamaica Center-bound [E] skips Briarwood. For service to this station, take the [E] to Jamaica-Van Wyck and transfer to a Manhattan-bound [E]. For service from this station, take the [E] or [F] to Kew Gardens-Union Tpke and transfer to a Jamaica Center-bound [E]. What's happening? Urgent electrical repairs. " +
        "[N] and [W] trains are delayed due to signal problems at Queensboro Plaza. Consider the [7] or [F] for alternate service between Queens and Manhattan. What's happening? Signal malfunction at Queensboro Plaza. " +
        "[4] [5] [6] Lexington Ave Line: Expect delays in both directions due to a sick customer at 125 St. MTA personnel are on scene. What's happening? Customer incident at 125 St. [accessibility icon] Customers needing accessibility should allow extra travel time."

    private val tierToText = mapOf(
        Tier.TIER_1 to TIER_1_SHORT,
        Tier.TIER_2 to TIER_2_MEDIUM,
        Tier.TIER_3 to TIER_3_LONG,
        Tier.TIER_4 to TIER_4_STRESS
    )

    val tiers: List<Tier> = Tier.entries

    fun getAlertText(tier: Tier): String = tierToText.getValue(tier)
}
