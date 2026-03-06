package com.commutebuddy.app

import android.util.Log

sealed class PipelineResult {
    /** No active alerts after filtering — good service, no Gemini call made. */
    data class GoodService(val status: CommuteStatus) : PipelineResult()

    /** Gemini returned a decision. */
    data class Decision(val status: CommuteStatus, val warning: String?) : PipelineResult()

    /** Daily/minute/cooldown cap hit — no Gemini call made. */
    data class RateLimited(val reason: String) : PipelineResult()

    /** Fetch, parse, or Gemini API error. Status is safe to BLE-send as a fallback. */
    data class Error(
        val status: CommuteStatus,
        val message: String,
        val exception: Exception? = null
    ) : PipelineResult()
}

/**
 * Encapsulates the full fetch -> parse -> filter -> Gemini -> deserialize pipeline.
 *
 * [generateContent] is a lambda wrapping the Gemini model call, making the pipeline
 * testable without a Firebase dependency in unit tests.
 *
 * [clock] returns the current time in milliseconds; injectable for testing.
 */
object CommutePipeline {

    private const val TAG = "CommutePipeline"

    suspend fun run(
        direction: String,
        profile: CommuteProfile,
        generateContent: suspend (String) -> String?,
        rateLimiter: ApiRateLimiter,
        clock: () -> Long = { System.currentTimeMillis() }
    ): PipelineResult {
        val nowSeconds = clock() / 1000

        // Step 1: Fetch
        val fetchResult = MtaAlertFetcher.fetchAlerts()
        if (fetchResult.isFailure) {
            val e = fetchResult.exceptionOrNull()
            Log.e(TAG, "MTA fetch failed", e)
            val msg = (e?.message ?: "Fetch failed").take(80)
            return PipelineResult.Error(
                status = CommuteStatus(
                    action = CommuteStatus.ACTION_NORMAL,
                    summary = msg,
                    affectedRoutes = "",
                    rerouteHint = null,
                    timestamp = nowSeconds
                ),
                message = msg,
                exception = e as? Exception
            )
        }
        val jsonString = fetchResult.getOrThrow()

        // Step 2: Parse
        val alerts = MtaAlertParser.parseAlerts(jsonString)
        if (alerts.isEmpty() && jsonString.isNotBlank()) {
            val msg = "Feed parse error: no entities parsed"
            return PipelineResult.Error(
                status = CommuteStatus(
                    action = CommuteStatus.ACTION_NORMAL,
                    summary = "Feed parse error",
                    affectedRoutes = "",
                    rerouteHint = null,
                    timestamp = nowSeconds
                ),
                message = msg
            )
        }

        // Step 3: Filter by route and active period
        val monitoredRoutes = profile.monitoredRoutes()
        val filtered = MtaAlertParser.filterByActivePeriod(
            MtaAlertParser.filterByRoutes(alerts, monitoredRoutes),
            nowSeconds
        )

        // Step 4: Good-service short-circuit — no rate limiter touched
        if (filtered.isEmpty()) {
            return PipelineResult.GoodService(
                CommuteStatus(
                    action = CommuteStatus.ACTION_NORMAL,
                    summary = "Good service",
                    affectedRoutes = "",
                    rerouteHint = null,
                    timestamp = nowSeconds
                )
            )
        }

        // Step 5: Rate-limit check
        val promptText = MtaAlertParser.buildPromptText(filtered, direction, nowSeconds)
        return when (val rl = rateLimiter.tryAcquire()) {
            is RateLimitResult.Denied -> PipelineResult.RateLimited(rl.reason)
            is RateLimitResult.Allowed -> {
                val warning = rl.warningMessage
                rateLimiter.setInFlight(true)
                try {
                    // Step 6: Gemini call
                    val rawText = generateContent(promptText)
                    if (rawText.isNullOrBlank()) {
                        val msg = "Empty response from AI"
                        return PipelineResult.Error(
                            status = CommuteStatus(
                                action = CommuteStatus.ACTION_NORMAL,
                                summary = msg,
                                affectedRoutes = "",
                                rerouteHint = null,
                                timestamp = nowSeconds
                            ),
                            message = msg
                        )
                    }

                    // Step 7: Deserialize
                    try {
                        val parsed = CommuteStatus.fromJson(rawText)
                        PipelineResult.Decision(parsed, warning)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse Gemini response", e)
                        val msg = (e.message ?: "Parse failed").take(80)
                        PipelineResult.Error(
                            status = CommuteStatus(
                                action = CommuteStatus.ACTION_NORMAL,
                                summary = msg,
                                affectedRoutes = "",
                                rerouteHint = null,
                                timestamp = nowSeconds
                            ),
                            message = msg,
                            exception = e
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini API error", e)
                    val msg = (e.message ?: "API error").take(80)
                    PipelineResult.Error(
                        status = CommuteStatus(
                            action = CommuteStatus.ACTION_NORMAL,
                            summary = msg,
                            affectedRoutes = "",
                            rerouteHint = null,
                            timestamp = nowSeconds
                        ),
                        message = msg,
                        exception = e
                    )
                } finally {
                    rateLimiter.setInFlight(false)
                }
            }
        }
    }
}
