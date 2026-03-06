package com.commutebuddy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderTest {

    private val defaultProfile = CommuteProfile.default()

    // -------------------------------------------------------------------------
    // COMMUTE PROFILE section -- dynamic content
    // -------------------------------------------------------------------------

    @Test
    fun generatedPrompt_containsToWorkLegLines() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("lines: [N, W]"))
        assertTrue(prompt.contains("lines: [4, 5]"))
        assertTrue(prompt.contains("lines: [6]"))
    }

    @Test
    fun generatedPrompt_containsToWorkLegDirections() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("direction: Manhattan-bound"))
        assertTrue(prompt.contains("direction: Downtown"))
    }

    @Test
    fun generatedPrompt_containsToWorkLegStations() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("from: Astoria"))
        assertTrue(prompt.contains("to: 59th St"))
        assertTrue(prompt.contains("from: 59th St"))
        assertTrue(prompt.contains("to: 14th St"))
        assertTrue(prompt.contains("from: 14th St"))
        assertTrue(prompt.contains("to: Spring St"))
    }

    @Test
    fun generatedPrompt_containsToHomeLegDirections() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("direction: Uptown"))
        assertTrue(prompt.contains("direction: Queens-bound"))
    }

    @Test
    fun generatedPrompt_containsAlternates() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("alternates: [F, R, 7]"))
    }

    @Test
    fun generatedPrompt_containsBothToWorkAndToHomeLabels() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("TO_WORK:"))
        assertTrue(prompt.contains("TO_HOME:"))
    }

    // -------------------------------------------------------------------------
    // Alternates referenced in ALTERNATE LINE EVALUATION section
    // -------------------------------------------------------------------------

    @Test
    fun generatedPrompt_alternateLineEvaluation_referencesAlternates() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("alternate lines (F, R, 7)"))
    }

    @Test
    fun generatedPrompt_alternateLineEvaluation_hasGuardClause() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("ONLY evaluate alternate lines if at least one primary leg"))
    }

    @Test
    fun generatedPrompt_customAlternates_reflectedInEvaluationSection() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(CommuteLeg(listOf("A"), "Downtown", "Jay St", "Fulton St")),
            toHomeLegs = listOf(CommuteLeg(listOf("A"), "Uptown", "Fulton St", "Jay St")),
            alternates = listOf("C", "E")
        )
        val prompt = SystemPromptBuilder.buildSystemPrompt(profile)
        assertTrue(prompt.contains("alternate lines (C, E)"))
        assertFalse(prompt.contains("alternate lines (F, R, 7)"))
    }

    // -------------------------------------------------------------------------
    // Static framework sections remain present
    // -------------------------------------------------------------------------

    @Test
    fun generatedPrompt_containsDecisionFrameworkSection() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("DECISION FRAMEWORK:"))
        assertTrue(prompt.contains("NORMAL:"))
        assertTrue(prompt.contains("MINOR_DELAYS:"))
        assertTrue(prompt.contains("REROUTE:"))
        assertTrue(prompt.contains("STAY_HOME:"))
    }

    @Test
    fun generatedPrompt_containsDirectionMatchingRulesSection() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("DIRECTION MATCHING RULES:"))
        assertTrue(prompt.contains("both directions"))
    }

    @Test
    fun generatedPrompt_containsAlertFreshnessRulesSection() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("ALERT FRESHNESS RULES:"))
        assertTrue(prompt.contains("active_period"))
        assertTrue(prompt.contains("ASSUME RESOLVED"))
    }

    @Test
    fun generatedPrompt_containsOutputSchema() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("Respond with ONLY a JSON object"))
        assertTrue(prompt.contains("\"action\""))
        assertTrue(prompt.contains("\"summary\""))
        assertTrue(prompt.contains("\"reroute_hint\""))
        assertTrue(prompt.contains("\"affected_routes\""))
    }

    @Test
    fun generatedPrompt_containsIntroLine() {
        val prompt = SystemPromptBuilder.buildSystemPrompt(defaultProfile)
        assertTrue(prompt.contains("You are a commute advisor for an NYC subway rider"))
    }

    // -------------------------------------------------------------------------
    // Custom profile -- verify dynamic substitution
    // -------------------------------------------------------------------------

    @Test
    fun generatedPrompt_customProfile_usesCustomLegData() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(
                CommuteLeg(listOf("L"), "Manhattan-bound", "Canarsie", "8th Ave")
            ),
            toHomeLegs = listOf(
                CommuteLeg(listOf("L"), "Brooklyn-bound", "8th Ave", "Canarsie")
            ),
            alternates = listOf("M", "J")
        )
        val prompt = SystemPromptBuilder.buildSystemPrompt(profile)
        assertTrue(prompt.contains("lines: [L]"))
        assertTrue(prompt.contains("from: Canarsie"))
        assertTrue(prompt.contains("to: 8th Ave"))
        assertTrue(prompt.contains("direction: Brooklyn-bound"))
        assertTrue(prompt.contains("alternates: [M, J]"))
        assertTrue(prompt.contains("alternate lines (M, J)"))
        assertFalse(prompt.contains("Astoria"))
        assertFalse(prompt.contains("Spring St"))
    }
}
