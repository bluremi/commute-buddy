package com.commutebuddy.app

import android.content.Context

/**
 * Common abstraction for watch notification targets (Garmin, Wear OS, etc.).
 * Each implementation sends a [CommuteStatus] to its watch type and no-ops
 * gracefully when its watch type is not available.
 */
interface WatchNotifier {
    fun initialize(context: Context)
    suspend fun notify(status: CommuteStatus)
}
