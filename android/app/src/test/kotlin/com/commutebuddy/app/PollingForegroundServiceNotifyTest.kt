package com.commutebuddy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PollingForegroundService.shouldNotifyWatches].
 *
 * Verifies that pipeline errors are suppressed (watches keep their last good status)
 * while successful results continue to be forwarded.
 */
class PollingForegroundServiceNotifyTest {

    private val anyStatus = CommuteStatus(
        action = CommuteStatus.ACTION_NORMAL,
        summary = "Good service",
        affectedRoutes = "",
        rerouteHint = null,
        timestamp = 0L
    )

    // -------------------------------------------------------------------------
    // Results that should NOT trigger a watch notification
    // -------------------------------------------------------------------------

    @Test
    fun `Error result is suppressed - watches retain last good status`() {
        val result = PipelineResult.Error(
            status = anyStatus,
            message = "Unable to resolve host: api.mta.info"
        )
        assertFalse(PollingForegroundService.shouldNotifyWatches(result))
    }

    @Test
    fun `RateLimited result is suppressed`() {
        val result = PipelineResult.RateLimited(reason = "daily cap reached")
        assertFalse(PollingForegroundService.shouldNotifyWatches(result))
    }

    // -------------------------------------------------------------------------
    // Results that SHOULD trigger a watch notification
    // -------------------------------------------------------------------------

    @Test
    fun `GoodService result is forwarded to watches`() {
        val result = PipelineResult.GoodService(status = anyStatus)
        assertTrue(PollingForegroundService.shouldNotifyWatches(result))
    }

    @Test
    fun `Decision result is forwarded to watches`() {
        val result = PipelineResult.Decision(status = anyStatus, warning = null)
        assertTrue(PollingForegroundService.shouldNotifyWatches(result))
    }

    @Test
    fun `Decision result with a warning is still forwarded to watches`() {
        val result = PipelineResult.Decision(status = anyStatus, warning = "Partial data")
        assertTrue(PollingForegroundService.shouldNotifyWatches(result))
    }
}
