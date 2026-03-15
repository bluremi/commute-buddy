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

/**
 * Broadcasts [status] to every notifier in [notifiers], isolating failures so that
 * one throwing notifier does not prevent the others from running.
 */
internal suspend fun notifyAll(
    notifiers: List<WatchNotifier>,
    status: CommuteStatus,
    onError: (WatchNotifier, Exception) -> Unit = { _, _ -> }
) {
    notifiers.forEach { notifier ->
        try { notifier.notify(status) } catch (e: Exception) {
            onError(notifier, e)
        }
    }
}
