package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the watch→phone command parser added in FEAT-16 increment 3:
 * [GarminNotifier.parsePollNowDirection]. Pure function — no Android framework or
 * ConnectIQ SDK required. The ConnectIQ SDK delivers a transmitted string as the
 * first element of a `List<Object>`, so inputs are modeled as single-element lists.
 */
class GarminNotifierParseTest {

    @Test
    fun `parses TO_WORK command`() {
        assertEquals("TO_WORK", GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:TO_WORK")))
    }

    @Test
    fun `parses TO_HOME command`() {
        assertEquals("TO_HOME", GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:TO_HOME")))
    }

    @Test
    fun `rejects unknown direction`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:TO_MARS")))
    }

    @Test
    fun `rejects wrong prefix`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf("REFRESH:TO_WORK")))
    }

    @Test
    fun `rejects bare direction without prefix`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf("TO_WORK")))
    }

    @Test
    fun `rejects empty prefix-only string`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:")))
    }

    @Test
    fun `is case-sensitive on the direction`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:to_work")))
    }

    @Test
    fun `rejects null message`() {
        assertNull(GarminNotifier.parsePollNowDirection(null))
    }

    @Test
    fun `rejects empty list`() {
        assertNull(GarminNotifier.parsePollNowDirection(emptyList()))
    }

    @Test
    fun `rejects non-string first element`() {
        assertNull(GarminNotifier.parsePollNowDirection(listOf(42)))
    }

    @Test
    fun `reads only the first element`() {
        // The SDK puts the transmitted content first; ignore anything trailing.
        assertEquals(
            "TO_WORK",
            GarminNotifier.parsePollNowDirection(listOf("POLL_NOW:TO_WORK", "ignored"))
        )
    }
}
