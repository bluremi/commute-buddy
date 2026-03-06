package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteProfileTest {

    // -------------------------------------------------------------------------
    // CommuteLeg serialization
    // -------------------------------------------------------------------------

    @Test
    fun commitLeg_roundTripSerialization() {
        val leg = CommuteLeg(
            lines = listOf("N", "W"),
            direction = "Manhattan-bound",
            fromStation = "Astoria",
            toStation = "59th St"
        )
        val restored = CommuteLeg.fromJson(leg.toJson())
        assertEquals(leg, restored)
    }

    @Test
    fun commitLeg_multipleLines_roundTripSerialization() {
        val leg = CommuteLeg(
            lines = listOf("4", "5", "6"),
            direction = "Downtown",
            fromStation = "Grand Central",
            toStation = "Union Sq"
        )
        val restored = CommuteLeg.fromJson(leg.toJson())
        assertEquals(leg, restored)
        assertEquals(3, restored.lines.size)
        assertEquals(listOf("4", "5", "6"), restored.lines)
    }

    // -------------------------------------------------------------------------
    // CommuteProfile serialization
    // -------------------------------------------------------------------------

    @Test
    fun commuteProfile_roundTripSerialization() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(
                CommuteLeg(listOf("N", "W"), "Manhattan-bound", "Astoria", "59th St"),
                CommuteLeg(listOf("4", "5"), "Downtown", "59th St", "14th St")
            ),
            toHomeLegs = listOf(
                CommuteLeg(listOf("4", "5"), "Uptown", "14th St", "59th St"),
                CommuteLeg(listOf("N", "W"), "Queens-bound", "59th St", "Astoria")
            ),
            alternates = listOf("F", "R", "7")
        )
        val restored = CommuteProfile.fromJson(profile.toJson())
        assertEquals(profile, restored)
    }

    @Test
    fun commuteProfile_emptyLegs_roundTripSerialization() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(CommuteLeg(listOf("A"), "Downtown", "Jay St", "Fulton St")),
            toHomeLegs = listOf(CommuteLeg(listOf("A"), "Uptown", "Fulton St", "Jay St")),
            alternates = emptyList()
        )
        val restored = CommuteProfile.fromJson(profile.toJson())
        assertEquals(profile, restored)
        assertTrue(restored.alternates.isEmpty())
    }

    // -------------------------------------------------------------------------
    // monitoredRoutes()
    // -------------------------------------------------------------------------

    @Test
    fun monitoredRoutes_includesBothDirectionLegsAndAlternates() {
        val profile = CommuteProfile(
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
        val routes = profile.monitoredRoutes()
        assertTrue(routes.contains("N"))
        assertTrue(routes.contains("W"))
        assertTrue(routes.contains("4"))
        assertTrue(routes.contains("5"))
        assertTrue(routes.contains("6"))
        assertTrue(routes.contains("F"))
        assertTrue(routes.contains("R"))
        assertTrue(routes.contains("7"))
    }

    @Test
    fun monitoredRoutes_deduplicatesAcrossDirections() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(CommuteLeg(listOf("N", "W"), "Manhattan-bound", "Astoria", "59th St")),
            toHomeLegs = listOf(CommuteLeg(listOf("N", "W"), "Queens-bound", "59th St", "Astoria")),
            alternates = listOf("N")
        )
        val routes = profile.monitoredRoutes()
        assertEquals(2, routes.size)
        assertTrue(routes.contains("N"))
        assertTrue(routes.contains("W"))
    }

    @Test
    fun monitoredRoutes_excludesUnusedLines() {
        val profile = CommuteProfile(
            toWorkLegs = listOf(CommuteLeg(listOf("A"), "Downtown", "Fulton St", "Jay St")),
            toHomeLegs = listOf(CommuteLeg(listOf("C"), "Uptown", "Jay St", "Fulton St")),
            alternates = listOf("E")
        )
        val routes = profile.monitoredRoutes()
        assertFalse(routes.contains("N"))
        assertFalse(routes.contains("6"))
        assertTrue(routes.contains("A"))
        assertTrue(routes.contains("C"))
        assertTrue(routes.contains("E"))
    }

    // -------------------------------------------------------------------------
    // Default profile
    // -------------------------------------------------------------------------

    @Test
    fun default_hasThreeToWorkLegs() {
        val profile = CommuteProfile.default()
        assertEquals(3, profile.toWorkLegs.size)
    }

    @Test
    fun default_hasThreeToHomeLegs() {
        val profile = CommuteProfile.default()
        assertEquals(3, profile.toHomeLegs.size)
    }

    @Test
    fun default_toWorkFirstLegIsNWManhattanBound() {
        val leg = CommuteProfile.default().toWorkLegs[0]
        assertEquals(listOf("N", "W"), leg.lines)
        assertEquals("Manhattan-bound", leg.direction)
        assertEquals("Astoria", leg.fromStation)
        assertEquals("59th St", leg.toStation)
    }

    @Test
    fun default_toHomeLastLegIsNWQueensBound() {
        val legs = CommuteProfile.default().toHomeLegs
        val leg = legs[legs.size - 1]
        assertEquals(listOf("N", "W"), leg.lines)
        assertEquals("Queens-bound", leg.direction)
        assertEquals("59th St", leg.fromStation)
        assertEquals("Astoria", leg.toStation)
    }

    @Test
    fun default_alternatesAreFR7() {
        val profile = CommuteProfile.default()
        assertEquals(listOf("F", "R", "7"), profile.alternates)
    }

    @Test
    fun default_monitoredRoutesContainsExpectedLines() {
        val routes = CommuteProfile.default().monitoredRoutes()
        assertEquals(setOf("N", "W", "4", "5", "6", "F", "R", "7"), routes)
    }
}
