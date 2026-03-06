package com.commutebuddy.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiRateLimiterTest {

    // Fake SharedPreferences backed by a HashMap — no Android runtime needed
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

    // Arbitrary stable epoch; the actual calendar date doesn't matter as long as
    // we derive all date strings from it using the same formatter the class uses.
    private val baseTime = 1_000_000_000_000L // ~Sep 9, 2001 01:46:40 UTC

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Date string that ApiRateLimiter will produce for baseTime. */
    private val todayString: String get() = sdf.format(Date(baseTime))

    /** Date string ApiRateLimiter will produce for baseTime + 24 hours. */
    private val tomorrowString: String get() = sdf.format(Date(baseTime + 24 * 60 * 60 * 1000L))

    private lateinit var prefs: FakeSharedPreferences
    private var currentTime = baseTime
    private lateinit var limiter: ApiRateLimiter

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        currentTime = baseTime
        limiter = ApiRateLimiter(prefs) { currentTime }
    }

    // -------------------------------------------------------------------------
    // Daily cap tests
    // -------------------------------------------------------------------------

    @Test
    fun `first request of the day is allowed`() {
        val result = limiter.tryAcquire()
        assertTrue(result is RateLimitResult.Allowed)
    }

    @Test
    fun `request at exactly DAILY_CAP is denied`() {
        // Pre-populate prefs so 49 requests are already recorded today
        prefs.edit()
            .putString("rate_limit_date", todayString)
            .putInt("rate_limit_count", ApiRateLimiter.DAILY_CAP - 1)
            .apply()

        // The 50th request should succeed
        val result50 = limiter.tryAcquire()
        assertTrue("50th request should be allowed", result50 is RateLimitResult.Allowed)

        // Advance past cooldown
        currentTime += ApiRateLimiter.COOLDOWN_MS + 1

        // The 51st request should be denied
        val result51 = limiter.tryAcquire()
        assertTrue("51st request should be denied", result51 is RateLimitResult.Denied.DailyCapExhausted)
    }

    @Test
    fun `date rollover resets daily counter`() {
        // Fill up the daily cap for today
        prefs.edit()
            .putString("rate_limit_date", todayString)
            .putInt("rate_limit_count", ApiRateLimiter.DAILY_CAP)
            .apply()

        // Verify cap is reached today
        val blockedResult = limiter.tryAcquire()
        assertTrue(blockedResult is RateLimitResult.Denied.DailyCapExhausted)

        // Advance clock to tomorrow (24 hours + cooldown buffer)
        currentTime += 24 * 60 * 60 * 1000L + ApiRateLimiter.COOLDOWN_MS + 1

        // Should be allowed on the new day
        val result = limiter.tryAcquire()
        assertTrue("Should be allowed after date rollover", result is RateLimitResult.Allowed)
    }

    @Test
    fun `daily counter persists across ApiRateLimiter instances`() {
        // Make one request on the first limiter instance
        val result1 = limiter.tryAcquire()
        assertTrue(result1 is RateLimitResult.Allowed)

        // Create a new instance backed by the same prefs (simulates app restart)
        currentTime += ApiRateLimiter.COOLDOWN_MS + 1
        val limiter2 = ApiRateLimiter(prefs) { currentTime }
        val result2 = limiter2.tryAcquire()

        assertTrue("Should be allowed on second instance", result2 is RateLimitResult.Allowed)
        // Both requests were recorded — count should now be 2
        assertEquals(2, prefs.getInt("rate_limit_count", 0))
    }

    // -------------------------------------------------------------------------
    // Per-minute cap tests
    // -------------------------------------------------------------------------

    @Test
    fun `eleventh request within 60s returns MinuteCapExhausted`() {
        // Make 10 requests spaced just past cooldown; total span = 9 * 3001ms = ~27s < 60s
        repeat(ApiRateLimiter.PER_MINUTE_CAP) { i ->
            currentTime = baseTime + i * (ApiRateLimiter.COOLDOWN_MS + 1)
            val result = limiter.tryAcquire()
            assertTrue("Request ${i + 1} should be allowed", result is RateLimitResult.Allowed)
        }

        // 11th request — still within the 60s window
        currentTime = baseTime + ApiRateLimiter.PER_MINUTE_CAP * (ApiRateLimiter.COOLDOWN_MS + 1)
        val result = limiter.tryAcquire()
        assertTrue(
            "11th request within 60s should be denied",
            result is RateLimitResult.Denied.MinuteCapExhausted
        )
    }

    @Test
    fun `per-minute cap resets after 60 seconds`() {
        // Fill the per-minute window (10 requests, last one at baseTime + 9 * 3001ms)
        val lastRequestOffset = (ApiRateLimiter.PER_MINUTE_CAP - 1) * (ApiRateLimiter.COOLDOWN_MS + 1)
        repeat(ApiRateLimiter.PER_MINUTE_CAP) { i ->
            currentTime = baseTime + i * (ApiRateLimiter.COOLDOWN_MS + 1)
            limiter.tryAcquire()
        }

        // Advance clock so the oldest entry (at baseTime) is >= 60s ago
        currentTime = baseTime + 60_001L

        val result = limiter.tryAcquire()
        assertTrue(
            "Should be allowed after minute window expires",
            result is RateLimitResult.Allowed
        )
    }

    // -------------------------------------------------------------------------
    // Cooldown tests
    // -------------------------------------------------------------------------

    @Test
    fun `immediate second request returns CooldownActive with remaining time`() {
        limiter.tryAcquire()

        // Second request with no time elapsed
        val result = limiter.tryAcquire()
        assertTrue(result is RateLimitResult.Denied.CooldownActive)
        val denied = result as RateLimitResult.Denied.CooldownActive
        assertTrue("Remaining time should be positive", denied.remainingMs > 0)
        assertTrue("Remaining time should not exceed COOLDOWN_MS", denied.remainingMs <= ApiRateLimiter.COOLDOWN_MS)
    }

    @Test
    fun `request after cooldown period is allowed`() {
        limiter.tryAcquire()

        currentTime += ApiRateLimiter.COOLDOWN_MS + 1
        val result = limiter.tryAcquire()
        assertTrue("Should be allowed after cooldown", result is RateLimitResult.Allowed)
    }

    // -------------------------------------------------------------------------
    // Single-flight tests
    // -------------------------------------------------------------------------

    @Test
    fun `tryAcquire returns RequestInFlight when flag is set`() {
        limiter.setInFlight(true)
        val result = limiter.tryAcquire()
        assertTrue(result is RateLimitResult.Denied.RequestInFlight)
    }

    @Test
    fun `tryAcquire succeeds after in-flight flag is cleared`() {
        limiter.setInFlight(true)
        limiter.setInFlight(false)
        val result = limiter.tryAcquire()
        assertTrue("Should be allowed once flag is cleared", result is RateLimitResult.Allowed)
    }

    // -------------------------------------------------------------------------
    // Budget warning tests
    // -------------------------------------------------------------------------

    @Test
    fun `budget warning is shown at 80 percent of daily cap`() {
        // Pre-populate so the next call becomes the 40th (80% of 50)
        val warningThreshold = (ApiRateLimiter.DAILY_CAP * 0.8).toInt() // 40
        prefs.edit()
            .putString("rate_limit_date", todayString)
            .putInt("rate_limit_count", warningThreshold - 1) // 39 recorded; next = 40th
            .apply()

        val result = limiter.tryAcquire()
        assertTrue(result is RateLimitResult.Allowed)
        val allowed = result as RateLimitResult.Allowed
        assertNotNull("Warning message should be present at 80% threshold", allowed.warningMessage)
        assertTrue(
            "Warning message should contain the updated count and cap",
            allowed.warningMessage!!.contains("$warningThreshold/${ApiRateLimiter.DAILY_CAP}")
        )
    }

    @Test
    fun `no budget warning below 80 percent of daily cap`() {
        // First request of the day — count becomes 1, well below 80%
        val result = limiter.tryAcquire()
        assertTrue(result is RateLimitResult.Allowed)
        assertNull("No warning below threshold", (result as RateLimitResult.Allowed).warningMessage)
    }

    // -------------------------------------------------------------------------
    // Layer priority tests
    // -------------------------------------------------------------------------

    @Test
    fun `in-flight check fires before cooldown check`() {
        // Trigger the cooldown window
        limiter.tryAcquire()
        // Also set in-flight so both layers would deny
        limiter.setInFlight(true)

        val result = limiter.tryAcquire()
        // Should report RequestInFlight, not CooldownActive
        assertTrue(
            "In-flight should take priority over cooldown",
            result is RateLimitResult.Denied.RequestInFlight
        )
    }

    @Test
    fun `daily cap fires when per-minute and cooldown are both clear`() {
        // Pre-populate with a full daily cap
        prefs.edit()
            .putString("rate_limit_date", todayString)
            .putInt("rate_limit_count", ApiRateLimiter.DAILY_CAP)
            .apply()

        // Fresh limiter: lastRequestTimeMs=0 (no cooldown) and minuteWindow is empty
        val result = limiter.tryAcquire()
        assertTrue(
            "Daily cap should fire even when cooldown and per-minute are clear",
            result is RateLimitResult.Denied.DailyCapExhausted
        )
    }

    // -------------------------------------------------------------------------
    // getDailyUsage tests
    // -------------------------------------------------------------------------

    @Test
    fun `getDailyUsage returns stored count for today`() {
        prefs.edit()
            .putString("rate_limit_date", todayString)
            .putInt("rate_limit_count", 17)
            .apply()

        val (count, cap) = limiter.getDailyUsage()
        assertEquals(17, count)
        assertEquals(ApiRateLimiter.DAILY_CAP, cap)
    }

    @Test
    fun `getDailyUsage returns zero on a new day`() {
        // Store a count for yesterday
        prefs.edit()
            .putString("rate_limit_date", tomorrowString) // wrong date relative to baseTime
            .putInt("rate_limit_count", 30)
            .apply()

        val (count, _) = limiter.getDailyUsage()
        assertEquals("Count should reset to 0 when stored date doesn't match today", 0, count)
    }

    @Test
    fun `getDailyUsage reflects count after tryAcquire calls`() {
        repeat(3) { i ->
            currentTime = baseTime + i * (ApiRateLimiter.COOLDOWN_MS + 1)
            limiter.tryAcquire()
        }

        val (count, _) = limiter.getDailyUsage()
        assertEquals(3, count)
    }
}
