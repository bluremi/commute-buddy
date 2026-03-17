package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOsNotifierTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun statusWith(
        action: String = CommuteStatus.ACTION_NORMAL,
        summary: String = "Good service",
        affectedRoutes: String = "",
        rerouteHint: String? = null,
        timestamp: Long = 1_700_000_000L
    ) = CommuteStatus(
        action = action,
        summary = summary,
        affectedRoutes = affectedRoutes,
        rerouteHint = rerouteHint,
        timestamp = timestamp
    )

    // -------------------------------------------------------------------------
    // Field presence
    // -------------------------------------------------------------------------

    @Test
    fun `all five fields present for full status with rerouteHint`() {
        val status = statusWith(
            action = CommuteStatus.ACTION_REROUTE,
            summary = "N train suspended",
            affectedRoutes = "N,W",
            rerouteHint = "Take the F train",
            timestamp = 1_700_000_001L
        )
        val map = WearOsNotifier.buildDataMap(status)

        assertTrue("action missing", map.containsKey("action"))
        assertTrue("summary missing", map.containsKey("summary"))
        assertTrue("affected_routes missing", map.containsKey("affected_routes"))
        assertTrue("reroute_hint missing", map.containsKey("reroute_hint"))
        assertTrue("timestamp missing", map.containsKey("timestamp"))
    }

    @Test
    fun `reroute_hint key absent when rerouteHint is null`() {
        val map = WearOsNotifier.buildDataMap(statusWith(rerouteHint = null))
        assertFalse("reroute_hint should be absent", map.containsKey("reroute_hint"))
    }

    // -------------------------------------------------------------------------
    // Field values
    // -------------------------------------------------------------------------

    @Test
    fun `field values match input CommuteStatus exactly`() {
        val status = statusWith(
            action = CommuteStatus.ACTION_MINOR_DELAYS,
            summary = "Delays on N",
            affectedRoutes = "N,W",
            rerouteHint = "Use the R",
            timestamp = 1_700_000_002L
        )
        val map = WearOsNotifier.buildDataMap(status)

        assertEquals(status.action, map["action"])
        assertEquals(status.summary, map["summary"])
        assertEquals(status.affectedRoutes, map["affected_routes"])
        assertEquals(status.rerouteHint, map["reroute_hint"])
        assertEquals(status.timestamp, map["timestamp"])
    }

    // -------------------------------------------------------------------------
    // Every ACTION_* variant round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `ACTION_NORMAL round-trips`() {
        val map = WearOsNotifier.buildDataMap(statusWith(action = CommuteStatus.ACTION_NORMAL))
        assertEquals(CommuteStatus.ACTION_NORMAL, map["action"])
    }

    @Test
    fun `ACTION_MINOR_DELAYS round-trips`() {
        val map = WearOsNotifier.buildDataMap(
            statusWith(action = CommuteStatus.ACTION_MINOR_DELAYS, affectedRoutes = "N")
        )
        assertEquals(CommuteStatus.ACTION_MINOR_DELAYS, map["action"])
    }

    @Test
    fun `ACTION_REROUTE round-trips`() {
        val map = WearOsNotifier.buildDataMap(
            statusWith(action = CommuteStatus.ACTION_REROUTE, affectedRoutes = "N,W")
        )
        assertEquals(CommuteStatus.ACTION_REROUTE, map["action"])
    }

    @Test
    fun `ACTION_STAY_HOME round-trips`() {
        val map = WearOsNotifier.buildDataMap(
            statusWith(action = CommuteStatus.ACTION_STAY_HOME, affectedRoutes = "N,W,4,5,6")
        )
        assertEquals(CommuteStatus.ACTION_STAY_HOME, map["action"])
    }

    // -------------------------------------------------------------------------
    // sent_at contract: timestamp field carries the status timestamp, not wall clock
    // -------------------------------------------------------------------------

    @Test
    fun `timestamp field carries status timestamp not system clock`() {
        val fixedTimestamp = 1_700_000_042L
        val map = WearOsNotifier.buildDataMap(statusWith(timestamp = fixedTimestamp))
        assertEquals(
            "timestamp should be the status timestamp, not System.currentTimeMillis()",
            fixedTimestamp,
            map["timestamp"]
        )
    }
}
