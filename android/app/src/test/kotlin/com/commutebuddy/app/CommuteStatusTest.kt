package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteStatusTest {

    // --- toConnectIQMap ---

    @Test
    fun `toConnectIQMap contains required keys`() {
        val status = CommuteStatus(
            action = CommuteStatus.ACTION_NORMAL,
            summary = "Good service on all lines",
            affectedRoutes = "",
            rerouteHint = null,
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertTrue(map.containsKey("action"))
        assertTrue(map.containsKey("summary"))
        assertTrue(map.containsKey("affected_routes"))
        assertTrue(map.containsKey("timestamp"))
    }

    @Test
    fun `toConnectIQMap omits reroute_hint when null`() {
        val status = CommuteStatus(
            action = CommuteStatus.ACTION_NORMAL,
            summary = "Good service",
            affectedRoutes = "",
            rerouteHint = null,
            timestamp = 1709312400L
        )
        assertFalse(status.toConnectIQMap().containsKey("reroute_hint"))
    }

    @Test
    fun `toConnectIQMap includes reroute_hint when present`() {
        val status = CommuteStatus(
            action = CommuteStatus.ACTION_REROUTE,
            summary = "N/W suspended",
            affectedRoutes = "N,W",
            rerouteHint = "Take R to 34 St",
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertTrue(map.containsKey("reroute_hint"))
        assertEquals("Take R to 34 St", map["reroute_hint"])
    }

    @Test
    fun `toConnectIQMap values match constructor fields`() {
        val status = CommuteStatus(
            action = CommuteStatus.ACTION_MINOR_DELAYS,
            summary = "Minor delays on N line",
            affectedRoutes = "N",
            rerouteHint = null,
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertEquals(CommuteStatus.ACTION_MINOR_DELAYS, map["action"])
        assertEquals("Minor delays on N line", map["summary"])
        assertEquals("N", map["affected_routes"])
        assertEquals(1709312400L, map["timestamp"])
    }

    @Test
    fun `toConnectIQMap value types are correct`() {
        val status = CommuteStatus(
            action = CommuteStatus.ACTION_NORMAL,
            summary = "Good service",
            affectedRoutes = "",
            rerouteHint = null,
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertTrue("action must be String", map["action"] is String)
        assertTrue("summary must be String", map["summary"] is String)
        assertTrue("affected_routes must be String", map["affected_routes"] is String)
        assertTrue("timestamp must be Long", map["timestamp"] is Long)
    }

    // --- statusLabel ---

    @Test
    fun `statusLabel maps NORMAL to Normal`() {
        assertEquals("Normal", CommuteStatus(CommuteStatus.ACTION_NORMAL, "Good service", "", null, 0L).statusLabel)
    }

    @Test
    fun `statusLabel maps MINOR_DELAYS to Minor Delays`() {
        assertEquals("Minor Delays", CommuteStatus(CommuteStatus.ACTION_MINOR_DELAYS, "Delays", "N", null, 0L).statusLabel)
    }

    @Test
    fun `statusLabel maps REROUTE to Reroute`() {
        assertEquals("Reroute", CommuteStatus(CommuteStatus.ACTION_REROUTE, "Reroute needed", "N,W", "Take R", 0L).statusLabel)
    }

    @Test
    fun `statusLabel maps STAY_HOME to Stay Home`() {
        assertEquals("Stay Home", CommuteStatus(CommuteStatus.ACTION_STAY_HOME, "Service suspended", "N,W,4,5,6", null, 0L).statusLabel)
    }

    // --- fromJson: valid cases ---

    @Test
    fun `fromJson parses NORMAL with empty affected_routes`() {
        val json = """{"action":"NORMAL","summary":"Good service on all lines","affected_routes":"","timestamp":1709312400}"""
        val status = CommuteStatus.fromJson(json)
        assertEquals(CommuteStatus.ACTION_NORMAL, status.action)
        assertEquals("Good service on all lines", status.summary)
        assertEquals("", status.affectedRoutes)
        assertNull(status.rerouteHint)
        assertEquals(1709312400L, status.timestamp)
    }

    @Test
    fun `fromJson parses MINOR_DELAYS`() {
        val json = """{"action":"MINOR_DELAYS","summary":"Minor delays on N","affected_routes":"N","timestamp":1709312400}"""
        val status = CommuteStatus.fromJson(json)
        assertEquals(CommuteStatus.ACTION_MINOR_DELAYS, status.action)
        assertEquals("N", status.affectedRoutes)
        assertNull(status.rerouteHint)
    }

    @Test
    fun `fromJson parses REROUTE with reroute_hint`() {
        val json = """{"action":"REROUTE","summary":"N/W suspended","affected_routes":"N,W","reroute_hint":"Take R to 34 St","timestamp":1709312400}"""
        val status = CommuteStatus.fromJson(json)
        assertEquals(CommuteStatus.ACTION_REROUTE, status.action)
        assertNotNull(status.rerouteHint)
        assertEquals("Take R to 34 St", status.rerouteHint)
    }

    @Test
    fun `fromJson parses STAY_HOME`() {
        val json = """{"action":"STAY_HOME","summary":"All subway service suspended","affected_routes":"N,W,4,5,6","timestamp":1709312400}"""
        val status = CommuteStatus.fromJson(json)
        assertEquals(CommuteStatus.ACTION_STAY_HOME, status.action)
    }

    @Test
    fun `fromJson strips markdown code fences`() {
        val json = "```json\n{\"action\":\"NORMAL\",\"summary\":\"Good service\",\"affected_routes\":\"\",\"timestamp\":1709312400}\n```"
        val status = CommuteStatus.fromJson(json)
        assertEquals(CommuteStatus.ACTION_NORMAL, status.action)
    }

    @Test
    fun `fromJson treats empty reroute_hint as null`() {
        val json = """{"action":"REROUTE","summary":"Delays","affected_routes":"N","reroute_hint":"","timestamp":1709312400}"""
        val status = CommuteStatus.fromJson(json)
        assertNull(status.rerouteHint)
    }

    // --- fromJson: invalid cases ---

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects invalid action`() {
        CommuteStatus.fromJson("""{"action":"UNKNOWN","summary":"Test","affected_routes":"N","timestamp":1709312400}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects missing action`() {
        CommuteStatus.fromJson("""{"summary":"Test","affected_routes":"N","timestamp":1709312400}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects empty summary`() {
        CommuteStatus.fromJson("""{"action":"NORMAL","summary":"","affected_routes":"","timestamp":1709312400}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects blank affected_routes for non-NORMAL action`() {
        CommuteStatus.fromJson("""{"action":"MINOR_DELAYS","summary":"Delays","affected_routes":"","timestamp":1709312400}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects missing timestamp`() {
        CommuteStatus.fromJson("""{"action":"NORMAL","summary":"Good service","affected_routes":""}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects invalid JSON`() {
        CommuteStatus.fromJson("not json at all")
    }
}
