package com.commutebuddy.app

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Sends [CommuteStatus] to paired Wear OS watches via the Wearable Data Layer API.
 *
 * No-ops gracefully when Google Play Services or the Wearable API is not available.
 * A unique [sent_at] timestamp is included in the data map to ensure [DataClient]
 * always triggers onDataChanged() even when the status payload content is identical.
 */
class WearOsNotifier : WatchNotifier {

    companion object {
        private const val TAG = "WearOsNotifier"
        const val DATA_PATH = "/commute-status"
    }

    private var appContext: Context? = null

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "WearOsNotifier initialized")
    }

    override suspend fun notify(status: CommuteStatus) {
        val ctx = appContext ?: run { Log.w(TAG, "notify skipped: not initialized"); return }
        try {
            val request = PutDataMapRequest.create(DATA_PATH)
            val dataMap = request.dataMap
            dataMap.putString("action", status.action)
            dataMap.putString("summary", status.summary)
            dataMap.putString("affected_routes", status.affectedRoutes)
            status.rerouteHint?.let { dataMap.putString("reroute_hint", it) }
            dataMap.putLong("timestamp", status.timestamp)
            dataMap.putLong("sent_at", System.currentTimeMillis())
            val putDataReq = request.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx)
                .putDataItem(putDataReq)
                .addOnSuccessListener { Log.d(TAG, "Wear OS data put success") }
                .addOnFailureListener { e -> Log.w(TAG, "Wear OS data put failed: ${e.message}") }
        } catch (e: ApiException) {
            Log.w(TAG, "Wear OS not available: ${e.statusCode} ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "WearOsNotifier.notify error: ${e.message}")
        }
    }
}
