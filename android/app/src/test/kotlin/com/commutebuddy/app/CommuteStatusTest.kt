package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteStatusTest {

    @Test
    fun `toConnectIQMap returns correct keys`() {
        val status = CommuteStatus(
            status = 1,
            routeString = "N,W",
            reason = "Signal problems",
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertTrue(map.containsKey("status"))
        assertTrue(map.containsKey("route_string"))
        assertTrue(map.containsKey("reason"))
        assertTrue(map.containsKey("timestamp"))
    }

    @Test
    fun `toConnectIQMap values match constructor fields`() {
        val status = CommuteStatus(
            status = 1,
            routeString = "N,W",
            reason = "Signal problems",
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertEquals(1, map["status"])
        assertEquals("N,W", map["route_string"])
        assertEquals("Signal problems", map["reason"])
        assertEquals(1709312400L, map["timestamp"])
    }

    @Test
    fun `toConnectIQMap value types are correct`() {
        val status = CommuteStatus(
            status = 0,
            routeString = "4,5,6",
            reason = "Good service",
            timestamp = 1709312400L
        )
        val map = status.toConnectIQMap()
        assertTrue("status must be Int", map["status"] is Int)
        assertTrue("route_string must be String", map["route_string"] is String)
        assertTrue("reason must be String", map["reason"] is String)
        assertTrue("timestamp must be Long", map["timestamp"] is Long)
    }

    @Test
    fun `toConnectIQMap for STATUS_NORMAL has status 0`() {
        val status = CommuteStatus(CommuteStatus.STATUS_NORMAL, "N,W", "Good service", 0L)
        assertEquals(0, status.toConnectIQMap()["status"])
    }

    @Test
    fun `toConnectIQMap for STATUS_ERROR has status 2`() {
        val status = CommuteStatus(CommuteStatus.STATUS_ERROR, "N,W", "Fetch failed", 0L)
        assertEquals(2, status.toConnectIQMap()["status"])
    }
}
