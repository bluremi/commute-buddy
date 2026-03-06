package com.commutebuddy.app

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed class RateLimitResult {
    data class Allowed(val warningMessage: String? = null) : RateLimitResult()
    sealed class Denied(val reason: String) : RateLimitResult() {
        data object DailyCapExhausted : Denied("Daily request limit reached (resets tomorrow)")
        data object MinuteCapExhausted : Denied("Too many requests this minute — wait 60s")
        data class CooldownActive(val remainingMs: Long) : Denied("Please wait ${remainingMs / 1000}s")
        data object RequestInFlight : Denied("Request already in progress")
    }
}

/**
 * Multi-layer rate limiter for the Gemini cloud API.
 *
 * Layer priority (checked in order):
 *   1. Single-flight: only one request in-flight at a time
 *   2. Cooldown: minimum gap between consecutive requests
 *   3. Per-minute cap: burst protection
 *   4. Daily cap: persisted nuclear backstop (survives restarts/crashes)
 *
 * Dependencies are injectable so the class is fully unit-testable without Android.
 */
class ApiRateLimiter(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DAILY_CAP = 50
        const val PER_MINUTE_CAP = 10
        const val COOLDOWN_MS = 3000L
        private const val WARNING_THRESHOLD = 0.8

        private const val PREF_DATE = "rate_limit_date"
        private const val PREF_COUNT = "rate_limit_count"
    }

    private val minuteWindow = mutableListOf<Long>()
    private var lastRequestTimeMs = 0L
    private val isInFlight = AtomicBoolean(false)

    /** Called by the caller to mark an API call as in-flight (true) or completed (false). */
    fun setInFlight(value: Boolean) {
        isInFlight.set(value)
    }

    @Synchronized
    fun tryAcquire(): RateLimitResult {
        // Layer 1 (priority): single-flight check
        if (isInFlight.get()) return RateLimitResult.Denied.RequestInFlight

        val now = clock()

        // Layer 2: minimum cooldown between requests
        if (lastRequestTimeMs > 0 && now - lastRequestTimeMs < COOLDOWN_MS) {
            val remaining = COOLDOWN_MS - (now - lastRequestTimeMs)
            return RateLimitResult.Denied.CooldownActive(remaining)
        }

        // Layer 3: per-minute burst cap (in-memory, not persisted)
        minuteWindow.removeAll { now - it >= 60_000L }
        if (minuteWindow.size >= PER_MINUTE_CAP) {
            return RateLimitResult.Denied.MinuteCapExhausted
        }

        // Layer 4: daily cap (persisted in SharedPreferences)
        val today = epochToDateString(now)
        val storedDate = prefs.getString(PREF_DATE, "")
        val storedCount = if (storedDate == today) prefs.getInt(PREF_COUNT, 0) else 0
        if (storedCount >= DAILY_CAP) {
            return RateLimitResult.Denied.DailyCapExhausted
        }

        // All layers passed — record this request
        val newCount = storedCount + 1
        prefs.edit()
            .putString(PREF_DATE, today)
            .putInt(PREF_COUNT, newCount)
            .apply()
        minuteWindow.add(now)
        lastRequestTimeMs = now

        val warning = if (newCount.toDouble() / DAILY_CAP >= WARNING_THRESHOLD) {
            "$newCount/$DAILY_CAP daily requests used"
        } else {
            null
        }
        return RateLimitResult.Allowed(warning)
    }

    /**
     * Returns the current daily usage as (count, cap).
     * Resets to 0 if the stored date is not today.
     */
    fun getDailyUsage(): Pair<Int, Int> {
        val today = epochToDateString(clock())
        val storedDate = prefs.getString(PREF_DATE, "")
        val count = if (storedDate == today) prefs.getInt(PREF_COUNT, 0) else 0
        return Pair(count, DAILY_CAP)
    }

    private fun epochToDateString(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))
    }
}
