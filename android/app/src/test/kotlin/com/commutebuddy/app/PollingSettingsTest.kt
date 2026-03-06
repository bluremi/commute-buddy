package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingSettingsTest {

    // -------------------------------------------------------------------------
    // CommuteWindow.isActive() boundary tests
    // -------------------------------------------------------------------------

    private val morningWindow = CommuteWindow(8, 0, 9, 30)   // 8:00–9:30
    private val eveningWindow = CommuteWindow(17, 30, 19, 0)  // 17:30–19:00

    @Test
    fun `isActive returns true for time inside window`() {
        assertTrue(morningWindow.isActive(8, 30))
        assertTrue(eveningWindow.isActive(18, 0))
    }

    @Test
    fun `isActive returns true at exact window start`() {
        assertTrue(morningWindow.isActive(8, 0))
        assertTrue(eveningWindow.isActive(17, 30))
    }

    @Test
    fun `isActive returns false at exact window end`() {
        // end is exclusive
        assertFalse(morningWindow.isActive(9, 30))
        assertFalse(eveningWindow.isActive(19, 0))
    }

    @Test
    fun `isActive returns false just before window start`() {
        assertFalse(morningWindow.isActive(7, 59))
        assertFalse(eveningWindow.isActive(17, 29))
    }

    @Test
    fun `isActive returns false just after window end`() {
        assertFalse(morningWindow.isActive(9, 31))
        assertFalse(eveningWindow.isActive(19, 1))
    }

    @Test
    fun `isActive returns false outside all windows`() {
        assertFalse(morningWindow.isActive(12, 0))
        assertFalse(eveningWindow.isActive(6, 0))
    }

    @Test
    fun `isActive handles midnight-spanning window`() {
        // 23:00–1:00 spans midnight
        val nightWindow = CommuteWindow(23, 0, 1, 0)
        assertTrue("23:30 should be active", nightWindow.isActive(23, 30))
        assertTrue("0:00 should be active", nightWindow.isActive(0, 0))
        assertTrue("0:59 should be active", nightWindow.isActive(0, 59))
        assertFalse("1:00 should not be active (exclusive end)", nightWindow.isActive(1, 0))
        assertFalse("12:00 should not be active", nightWindow.isActive(12, 0))
        assertFalse("22:59 should not be active", nightWindow.isActive(22, 59))
    }

    @Test
    fun `isActive handles single-minute window`() {
        val window = CommuteWindow(8, 0, 8, 1)
        assertTrue(window.isActive(8, 0))
        assertFalse(window.isActive(8, 1))
        assertFalse(window.isActive(7, 59))
    }

    // -------------------------------------------------------------------------
    // JSON round-trip tests
    // -------------------------------------------------------------------------

    @Test
    fun `PollingSettings round-trips through JSON`() {
        val original = PollingSettings(
            enabled = true,
            windows = listOf(
                CommuteWindow(8, 0, 9, 30),
                CommuteWindow(17, 30, 19, 0)
            ),
            intervalMinutes = 7
        )
        val json = original.toJson().toString()
        val restored = PollingSettings.fromJson(org.json.JSONObject(json))
        assertEquals(original, restored)
    }

    @Test
    fun `PollingSettings round-trips with enabled=false`() {
        val original = PollingSettings.default()
        val json = original.toJson().toString()
        val restored = PollingSettings.fromJson(org.json.JSONObject(json))
        assertEquals(original, restored)
        assertFalse(restored.enabled)
    }

    @Test
    fun `CommuteWindow round-trips through JSON`() {
        val window = CommuteWindow(23, 45, 1, 15)
        val restored = CommuteWindow.fromJson(window.toJson())
        assertEquals(window, restored)
    }

    @Test
    fun `PollingSettings default values are correct`() {
        val defaults = PollingSettings.default()
        assertFalse(defaults.enabled)
        assertEquals(5, defaults.intervalMinutes)
        assertEquals(2, defaults.windows.size)

        val morning = defaults.windows[0]
        assertEquals(8, morning.startHour)
        assertEquals(0, morning.startMinute)
        assertEquals(9, morning.endHour)
        assertEquals(30, morning.endMinute)

        val evening = defaults.windows[1]
        assertEquals(17, evening.startHour)
        assertEquals(30, evening.startMinute)
        assertEquals(19, evening.endHour)
        assertEquals(0, evening.endMinute)
    }
}
