package com.commutebuddy.app

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PollingForegroundService.computeNextAlarmTimeMs].
 *
 * All tests call the pure companion-object function with a fixed `nowMs` so they are
 * deterministic and require no Android framework.
 */
class PollingForegroundServiceSchedulingTest {

    // Default windows: 08:00–09:30 and 17:30–19:00
    private val morningWindow = CommuteWindow(8, 0, 9, 30)
    private val eveningWindow = CommuteWindow(17, 30, 19, 0)
    private val defaultWindows = listOf(morningWindow, eveningWindow)
    private val weekdays = PollingSettings.DEFAULT_ACTIVE_DAYS  // Mon–Fri

    /** Builds an epoch ms for the current-week occurrence of [dayOfWeek] at [hour]:[minute]. */
    private fun epochAt(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun settingsFor(
        activeDays: Set<Int> = weekdays,
        backgroundPolling: Boolean = true,
        intervalMinutes: Int = 5,
        windows: List<CommuteWindow> = defaultWindows
    ) = PollingSettings(
        enabled = true,
        windows = windows,
        intervalMinutes = intervalMinutes,
        activeDays = activeDays,
        backgroundPolling = backgroundPolling
    )

    // -------------------------------------------------------------------------
    // Tier 1: Active day + inside window → interval-based
    // -------------------------------------------------------------------------

    @Test
    fun `tier 1 - active weekday inside morning window returns interval offset`() {
        val nowMs = epochAt(Calendar.MONDAY, 8, 30)  // Monday 08:30, inside 08:00–09:30
        val settings = settingsFor(intervalMinutes = 5)
        val result = PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs)
        assertEquals(nowMs + 5 * 60_000L, result)
    }

    @Test
    fun `tier 1 - active weekday inside evening window returns interval offset`() {
        val nowMs = epochAt(Calendar.WEDNESDAY, 18, 0)  // Wednesday 18:00, inside 17:30–19:00
        val settings = settingsFor(intervalMinutes = 10)
        val result = PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs)
        assertEquals(nowMs + 10 * 60_000L, result)
    }

    @Test
    fun `tier 1 - inactive day inside a window time is NOT treated as tier 1`() {
        // Saturday at 08:30 — time matches morning window but Saturday is not an active day
        val nowMs = epochAt(Calendar.SATURDAY, 8, 30)
        val settings = settingsFor(intervalMinutes = 5)
        val result = PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs)
        // Should NOT be interval-based — result must differ from nowMs + 5 min
        assertTrue(result != nowMs + 5 * 60_000L)
    }

    // -------------------------------------------------------------------------
    // Tier 2: Background ON, outside intensive times → hourly (or sooner window start)
    // -------------------------------------------------------------------------

    @Test
    fun `tier 2 - inactive day background ON returns top of next hour`() {
        // Saturday 10:00 — no active-day window starts within the hour
        val nowMs = epochAt(Calendar.SATURDAY, 10, 0)
        val settings = settingsFor(backgroundPolling = true)
        val expectedTopOfHour = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedTopOfHour, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    @Test
    fun `tier 2 - active day outside window background ON returns top of next hour when window is far`() {
        // Monday 10:00 — next window (evening 17:30) is 7.5 h away, far past the next hour
        val nowMs = epochAt(Calendar.MONDAY, 10, 0)
        val settings = settingsFor(backgroundPolling = true)
        val expectedTopOfHour = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedTopOfHour, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    @Test
    fun `tier 2 - window starting within the next hour fires at that window start`() {
        // Monday 07:45 — morning window starts at 08:00 (15 min away, before top of hour 08:45 ... wait)
        // 07:45 + 1 hour = 08:45. Morning window is 08:00, which is before 08:45 → fires at 08:00.
        val nowMs = epochAt(Calendar.MONDAY, 7, 45)
        val settings = settingsFor(backgroundPolling = true)
        val expectedWindowStart = PollingForegroundService.nextOccurrenceOnActiveDay(8, 0, nowMs, weekdays)
        assertEquals(expectedWindowStart, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    @Test
    fun `tier 2 - all days inactive background ON returns hourly only`() {
        val nowMs = epochAt(Calendar.SATURDAY, 10, 0)
        val settings = settingsFor(activeDays = emptySet(), backgroundPolling = true)
        val expectedTopOfHour = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedTopOfHour, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    // -------------------------------------------------------------------------
    // Tier 3: Background OFF, outside intensive times → next active-day window start
    // -------------------------------------------------------------------------

    @Test
    fun `tier 3 - active day outside window background OFF fires at next window on active day`() {
        // Monday 10:00 — outside both windows, background OFF
        // nextOccurrenceOnActiveDay(17,30, Monday 10:00, weekdays) = Monday 17:30 (still today)
        // nextOccurrenceOnActiveDay(8,0, Monday 10:00, weekdays) = Tuesday 08:00 (08:00 passed today)
        // min = Monday 17:30
        val nowMs = epochAt(Calendar.MONDAY, 10, 0)
        val settings = settingsFor(backgroundPolling = false)
        val expectedEveningStart = PollingForegroundService.nextOccurrenceOnActiveDay(17, 30, nowMs, weekdays)
        assertEquals(expectedEveningStart, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    @Test
    fun `tier 3 - inactive day background OFF fires at next active-day window start`() {
        // Saturday 10:00 — background OFF → skip to Monday 08:00
        val nowMs = epochAt(Calendar.SATURDAY, 10, 0)
        val settings = settingsFor(backgroundPolling = false)
        // Both window starts land on Monday (08:00 and 17:30); Monday 08:00 is earlier
        val expectedMondayMorning = PollingForegroundService.nextOccurrenceOnActiveDay(8, 0, nowMs, weekdays)
        assertEquals(expectedMondayMorning, PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    @Test
    fun `tier 3 - all days inactive background OFF returns far-future sentinel`() {
        val nowMs = epochAt(Calendar.SATURDAY, 10, 0)
        val settings = settingsFor(activeDays = emptySet(), backgroundPolling = false)
        assertEquals(nowMs + PollingForegroundService.FAR_FUTURE_OFFSET_MS,
            PollingForegroundService.computeNextAlarmTimeMs(settings, nowMs))
    }

    // -------------------------------------------------------------------------
    // nextOccurrenceOnActiveDay helper
    // -------------------------------------------------------------------------

    @Test
    fun `nextOccurrenceOnActiveDay skips weekend to reach Monday`() {
        // Saturday 10:00 → next Monday 08:00
        val nowMs = epochAt(Calendar.SATURDAY, 10, 0)
        val result = PollingForegroundService.nextOccurrenceOnActiveDay(8, 0, nowMs, weekdays)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `nextOccurrenceOnActiveDay advances past today if time already elapsed`() {
        // Monday 10:00 — asking for 08:00 on a weekday → next occurrence is Tuesday 08:00
        val nowMs = epochAt(Calendar.MONDAY, 10, 0)
        val result = PollingForegroundService.nextOccurrenceOnActiveDay(8, 0, nowMs, weekdays)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.TUESDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `nextOccurrenceOnActiveDay returns same day if time is still ahead`() {
        // Monday 07:00 — asking for 08:00 on a weekday → Monday 08:00
        val nowMs = epochAt(Calendar.MONDAY, 7, 0)
        val result = PollingForegroundService.nextOccurrenceOnActiveDay(8, 0, nowMs, weekdays)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
    }

    // -------------------------------------------------------------------------
    // resolvePollingDirection
    // -------------------------------------------------------------------------

    @Test
    fun `resolvePollingDirection - inside morning window returns TO_WORK`() {
        val settings = settingsFor()
        val result = PollingForegroundService.resolvePollingDirection(settings, 8, 30, null)
        assertEquals("TO_WORK", result)
    }

    @Test
    fun `resolvePollingDirection - inside evening window returns TO_HOME`() {
        val settings = settingsFor()
        val result = PollingForegroundService.resolvePollingDirection(settings, 18, 0, null)
        assertEquals("TO_HOME", result)
    }

    @Test
    fun `resolvePollingDirection - outside both windows falls back to last polled direction`() {
        val settings = settingsFor()
        val result = PollingForegroundService.resolvePollingDirection(settings, 12, 0, "TO_HOME")
        assertEquals("TO_HOME", result)
    }

    @Test
    fun `resolvePollingDirection - outside both windows with no prior poll defaults to TO_WORK`() {
        val settings = settingsFor()
        val result = PollingForegroundService.resolvePollingDirection(settings, 12, 0, null)
        assertEquals("TO_WORK", result)
    }
}
