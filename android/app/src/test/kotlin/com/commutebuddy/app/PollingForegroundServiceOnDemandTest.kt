package com.commutebuddy.app

import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the on-demand poll path added in FEAT-16 increment 1:
 * [PollingForegroundService.executeDirectionalPoll] (explicit-direction pipeline run,
 * shared by the scheduled and on-demand poll) and
 * [PollingForegroundService.runIfNotInFlight] (mutex-based serialization shared by both).
 *
 * All tests call pure/injectable companion-object functions — no Android framework or
 * running Service instance required, matching the pattern in
 * [PollingForegroundServiceSchedulingTest] and [PollingForegroundServiceRetryTest].
 */
class PollingForegroundServiceOnDemandTest {

    // Minimal fake SharedPreferences (no Android runtime needed)
    private class FakeSharedPreferences : SharedPreferences {
        val store = mutableMapOf<String, Any?>()

        inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun clear() = apply { pending.clear() }
            override fun commit(): Boolean { store.putAll(pending); return true }
            override fun apply() { store.putAll(pending) }
        }

        override fun edit() = FakeEditor()
        override fun getString(key: String, defValue: String?) = store[key] as? String ?: defValue
        override fun getInt(key: String, defValue: Int) = store[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = store[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = store[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = store[key] as? Boolean ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String>) ?: defValues
        override fun contains(key: String) = store.containsKey(key)
        override fun getAll(): Map<String, *> = store.toMap()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    }

    private val profile = CommuteProfile.default()
    private lateinit var rateLimiter: ApiRateLimiter

    @Before
    fun setup() {
        rateLimiter = ApiRateLimiter(FakeSharedPreferences())
        mockkObject(CommutePipeline)
    }

    @After
    fun teardown() {
        unmockkObject(CommutePipeline)
    }

    // -------------------------------------------------------------------------
    // executeDirectionalPoll — explicit direction passes straight through
    // -------------------------------------------------------------------------

    @Test
    fun `executeDirectionalPoll forwards the explicit direction to CommutePipeline unchanged`() = runTest {
        val directionSlot = slot<String>()
        val goodService = PipelineResult.GoodService(
            CommuteStatus(CommuteStatus.ACTION_NORMAL, "Good service", "", null, 0L)
        )
        coEvery {
            CommutePipeline.run(direction = capture(directionSlot), profile = any(), generateContent = any(), rateLimiter = any(), clock = any())
        } returns goodService

        val result = PollingForegroundService.executeDirectionalPoll(
            direction = "TO_HOME",
            profile = profile,
            generateContent = { "unused" },
            rateLimiter = rateLimiter,
            nextAlarmTimeMs = Long.MAX_VALUE
        )

        assertEquals("TO_HOME", directionSlot.captured)
        assertEquals(goodService, result)
    }

    @Test
    fun `executeDirectionalPoll does not re-derive direction from time of day`() = runTest {
        val directionSlot = slot<String>()
        val goodService = PipelineResult.GoodService(
            CommuteStatus(CommuteStatus.ACTION_NORMAL, "Good service", "", null, 0L)
        )
        coEvery {
            CommutePipeline.run(direction = capture(directionSlot), profile = any(), generateContent = any(), rateLimiter = any(), clock = any())
        } returns goodService

        // TO_WORK is passed explicitly even though this looks like an evening call site —
        // executeDirectionalPoll must never call resolvePollingDirection itself.
        PollingForegroundService.executeDirectionalPoll(
            direction = "TO_WORK",
            profile = profile,
            generateContent = { "unused" },
            rateLimiter = rateLimiter,
            nextAlarmTimeMs = Long.MAX_VALUE
        )

        assertEquals("TO_WORK", directionSlot.captured)
    }

    @Test
    fun `executeDirectionalPoll still retries fetch errors via runWithRetry`() = runTest {
        var callCount = 0
        val fetchError = PipelineResult.Error(
            CommuteStatus(CommuteStatus.ACTION_NORMAL, "err", "", null, 0L),
            "Fetch failed",
            java.io.IOException("DNS failure")
        )
        val goodService = PipelineResult.GoodService(
            CommuteStatus(CommuteStatus.ACTION_NORMAL, "Good service", "", null, 0L)
        )
        coEvery {
            CommutePipeline.run(direction = any(), profile = any(), generateContent = any(), rateLimiter = any(), clock = any())
        } answers {
            callCount++
            if (callCount < 2) fetchError else goodService
        }

        val result = PollingForegroundService.executeDirectionalPoll(
            direction = "TO_WORK",
            profile = profile,
            generateContent = { "unused" },
            rateLimiter = rateLimiter,
            nextAlarmTimeMs = Long.MAX_VALUE
        )

        assertEquals(goodService, result)
        assertEquals(2, callCount)
    }

    // -------------------------------------------------------------------------
    // runIfNotInFlight — serialization of overlapping poll requests
    // -------------------------------------------------------------------------

    @Test
    fun `runIfNotInFlight executes and returns block result when mutex is free`() = runTest {
        val mutex = Mutex()
        var executed = false

        val result = PollingForegroundService.runIfNotInFlight(mutex) {
            executed = true
            "poll-result"
        }

        assertTrue(executed)
        assertEquals("poll-result", result)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `runIfNotInFlight skips block and reports skip when mutex already held`() = runTest {
        val mutex = Mutex()
        mutex.lock()
        var executed = false
        var skipped = false

        val result = PollingForegroundService.runIfNotInFlight(mutex, onSkipped = { skipped = true }) {
            executed = true
            "poll-result"
        }

        assertFalse(executed)
        assertTrue(skipped)
        assertNull(result)
    }

    @Test
    fun `runIfNotInFlight releases the mutex even if block throws`() = runTest {
        val mutex = Mutex()

        try {
            PollingForegroundService.runIfNotInFlight(mutex) {
                error("pipeline blew up")
            }
        } catch (_: IllegalStateException) {
            // expected
        }

        assertFalse(mutex.isLocked)
    }

    @Test
    fun `a rapid second on-demand request is serialized out while the first is in flight`() = runTest {
        val mutex = Mutex()
        mutex.lock() // simulates a scheduled poll (or a first tap) already in flight
        var secondRequestRan = false
        var secondRequestSkipped = false

        val secondResult = PollingForegroundService.runIfNotInFlight(
            mutex,
            onSkipped = { secondRequestSkipped = true }
        ) {
            secondRequestRan = true
        }

        assertFalse("overlapping on-demand poll must not run the pipeline again", secondRequestRan)
        assertTrue(secondRequestSkipped)
        assertNull(secondResult)
    }
}
