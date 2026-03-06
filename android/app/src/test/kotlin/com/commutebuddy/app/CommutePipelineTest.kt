package com.commutebuddy.app

import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommutePipelineTest {

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

    private val baseTime = 1_000_000_000_000L
    private var currentTime = baseTime
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var rateLimiter: ApiRateLimiter
    private val profile = CommuteProfile.default()

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        currentTime = baseTime
        rateLimiter = ApiRateLimiter(prefs) { currentTime }
        mockkObject(MtaAlertFetcher)
    }

    @After
    fun teardown() {
        unmockkObject(MtaAlertFetcher)
    }

    // -------------------------------------------------------------------------

    @Test
    fun `empty filtered alerts produce GoodService without touching rateLimiter`() = runBlocking {
        // Feed with no entities — nothing survives filtering
        coEvery { MtaAlertFetcher.fetchAlerts() } returns Result.success("""{"entity":[]}""")

        // generateContent throws if called — proves rateLimiter.tryAcquire() was not invoked
        // (if tryAcquire were called and allowed, generateContent would be reached and throw,
        //  producing PipelineResult.Error, not GoodService)
        val result = CommutePipeline.run(
            direction = "TO_WORK",
            profile = profile,
            generateContent = { error("generateContent must not be called for GoodService") },
            rateLimiter = rateLimiter,
            clock = { currentTime }
        )

        assertTrue("Expected GoodService but got $result", result is PipelineResult.GoodService)
    }

    @Test
    fun `RateLimited returned when tryAcquire is denied`() = runBlocking {
        // Provide an alert on the N train that survives filtering
        coEvery { MtaAlertFetcher.fetchAlerts() } returns Result.success(activeAlertJson("N"))

        // Exhaust the daily cap so tryAcquire returns Denied
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(currentTime))
        prefs.edit()
            .putString("rate_limit_date", today)
            .putInt("rate_limit_count", ApiRateLimiter.DAILY_CAP)
            .apply()

        // generateContent throws if called — proves we short-circuit before the model
        val result = CommutePipeline.run(
            direction = "TO_WORK",
            profile = profile,
            generateContent = { error("generateContent must not be called when rate limited") },
            rateLimiter = rateLimiter,
            clock = { currentTime }
        )

        assertTrue("Expected RateLimited but got $result", result is PipelineResult.RateLimited)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Minimal GTFS-RT JSON with one active alert for [route], active around [baseTime]. */
    private fun activeAlertJson(route: String): String {
        val nowSeconds = currentTime / 1000
        return """
        {
          "entity": [{
            "id": "test-$route",
            "alert": {
              "header_text": {
                "translation": [{"language": "en", "text": "Test alert on $route train"}]
              },
              "informed_entity": [{"route_id": "$route"}],
              "active_period": [{"start": ${nowSeconds - 3600}, "end": ${nowSeconds + 3600}}]
            }
          }]
        }
        """.trimIndent()
    }
}
