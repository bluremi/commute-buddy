package com.commutebuddy.app

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for backoff helpers and retry loop in [PollingForegroundService].
 *
 * All tests call pure companion-object functions and use [runTest] for coroutine
 * suspension — no Android framework required.
 */
class PollingForegroundServiceRetryTest {

    private val dummyStatus = CommuteStatus(
        action = CommuteStatus.ACTION_NORMAL,
        summary = "test",
        affectedRoutes = "",
        rerouteHint = null,
        timestamp = 0L
    )

    private fun fetchError(e: Exception? = IOException("DNS failure")) =
        PipelineResult.Error(dummyStatus, e?.message ?: "error", e)

    // -------------------------------------------------------------------------
    // computeBackoffDelayMs
    // -------------------------------------------------------------------------

    @Test
    fun `computeBackoffDelayMs - attempt 0 is in range 25500 to 34500`() {
        repeat(20) {
            val d = PollingForegroundService.computeBackoffDelayMs(0)
            assertTrue("delay=$d", d in 25_500L..34_500L)
        }
    }

    @Test
    fun `computeBackoffDelayMs - attempt 1 is in range 51000 to 69000`() {
        repeat(20) {
            val d = PollingForegroundService.computeBackoffDelayMs(1)
            assertTrue("delay=$d", d in 51_000L..69_000L)
        }
    }

    @Test
    fun `computeBackoffDelayMs - attempt 2 is in range 102000 to 138000`() {
        repeat(20) {
            val d = PollingForegroundService.computeBackoffDelayMs(2)
            assertTrue("delay=$d", d in 102_000L..138_000L)
        }
    }

    @Test
    fun `computeBackoffDelayMs - attempt 3 is in range 204000 to 276000`() {
        repeat(20) {
            val d = PollingForegroundService.computeBackoffDelayMs(3)
            assertTrue("delay=$d", d in 204_000L..276_000L)
        }
    }

    @Test
    fun `computeBackoffDelayMs - attempts 4 and above clamp to same range`() {
        repeat(20) {
            val d4 = PollingForegroundService.computeBackoffDelayMs(4)
            val d10 = PollingForegroundService.computeBackoffDelayMs(10)
            assertTrue("delay4=$d4", d4 in 408_000L..552_000L)
            assertTrue("delay10=$d10", d10 in 408_000L..552_000L)
        }
    }

    // -------------------------------------------------------------------------
    // isFetchError
    // -------------------------------------------------------------------------

    @Test
    fun `isFetchError - IOException-backed Error returns true`() {
        assertTrue(PollingForegroundService.isFetchError(fetchError(IOException("DNS failure"))))
    }

    @Test
    fun `isFetchError - IOException subclass returns true`() {
        assertTrue(PollingForegroundService.isFetchError(fetchError(java.net.SocketTimeoutException("timeout"))))
    }

    @Test
    fun `isFetchError - Error with RuntimeException returns false`() {
        assertFalse(PollingForegroundService.isFetchError(fetchError(RuntimeException("boom"))))
    }

    @Test
    fun `isFetchError - Error with null exception returns false`() {
        assertFalse(PollingForegroundService.isFetchError(fetchError(null)))
    }

    @Test
    fun `isFetchError - GoodService returns false`() {
        assertFalse(PollingForegroundService.isFetchError(PipelineResult.GoodService(dummyStatus)))
    }

    @Test
    fun `isFetchError - Decision returns false`() {
        assertFalse(PollingForegroundService.isFetchError(PipelineResult.Decision(dummyStatus, null)))
    }

    @Test
    fun `isFetchError - RateLimited returns false`() {
        assertFalse(PollingForegroundService.isFetchError(PipelineResult.RateLimited("limit hit")))
    }

    // -------------------------------------------------------------------------
    // runWithRetry
    // -------------------------------------------------------------------------

    @Test
    fun `runWithRetry - returns immediately on first-try success`() = runTest {
        val good = PipelineResult.GoodService(dummyStatus)
        val result = PollingForegroundService.runWithRetry(
            runPipeline = { good },
            nextAlarmTimeMs = Long.MAX_VALUE,
            nowMs = { 0L }
        )
        assertEquals(good, result)
    }

    @Test
    fun `runWithRetry - retries on fetch error and returns success when pipeline clears`() = runTest {
        var callCount = 0
        val good = PipelineResult.GoodService(dummyStatus)
        val result = PollingForegroundService.runWithRetry(
            runPipeline = {
                callCount++
                if (callCount < 3) fetchError() else good
            },
            nextAlarmTimeMs = Long.MAX_VALUE,
            nowMs = { 0L }
        )
        assertEquals(good, result)
        assertEquals(3, callCount)
    }

    @Test
    fun `runWithRetry - stops when backoff would exceed time to next alarm`() = runTest {
        var callCount = 0
        // timeToAlarm = 10_000ms; minimum backoff ~25_500ms — always exceeds alarm window
        val result = PollingForegroundService.runWithRetry(
            runPipeline = {
                callCount++
                fetchError()
            },
            nextAlarmTimeMs = 10_000L,
            nowMs = { 0L }
        )
        assertTrue(result is PipelineResult.Error)
        assertEquals(1, callCount)  // initial call only, no retries
    }

    @Test
    fun `runWithRetry - non-fetch errors do not trigger retries`() = runTest {
        var callCount = 0
        val geminiError = PipelineResult.Error(dummyStatus, "API error", RuntimeException("API error"))
        val result = PollingForegroundService.runWithRetry(
            runPipeline = {
                callCount++
                geminiError
            },
            nextAlarmTimeMs = Long.MAX_VALUE,
            nowMs = { 0L }
        )
        assertEquals(geminiError, result)
        assertEquals(1, callCount)
    }

    @Test
    fun `runWithRetry - RateLimited does not trigger retries`() = runTest {
        var callCount = 0
        val rateLimited = PipelineResult.RateLimited("daily cap")
        val result = PollingForegroundService.runWithRetry(
            runPipeline = {
                callCount++
                rateLimited
            },
            nextAlarmTimeMs = Long.MAX_VALUE,
            nowMs = { 0L }
        )
        assertEquals(rateLimited, result)
        assertEquals(1, callCount)
    }
}
