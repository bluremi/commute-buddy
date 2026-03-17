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

        /**
         * Builds the core data map fields from a [CommuteStatus].
         * Does NOT include [sent_at] — that is appended by [notify] to guarantee uniqueness.
         */
        internal fun buildDataMap(status: CommuteStatus): Map<String, Any> = buildMap {
            put("action", status.action)
            put("summary", status.summary)
            put("affected_routes", status.affectedRoutes)
            status.rerouteHint?.let { put("reroute_hint", it) }
            put("timestamp", status.timestamp)
        }
    }

    /** Called after the first successful [putDataItem] — use to update connection status UI. */
    var onConnected: (() -> Unit)? = null

    private var appContext: Context? = null

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "WearOsNotifier initialized")
    }

    /** Proactively check if any paired Wear OS nodes are reachable and fire [onConnected] if so. */
    fun checkConnected() {
        val ctx = appContext ?: return
        try {
            Wearable.getNodeClient(ctx)
                .connectedNodes
                .addOnSuccessListener { nodes ->
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "Wear OS nodes reachable: ${nodes.map { it.displayName }}")
                        onConnected?.invoke()
                    } else {
                        Log.d(TAG, "No Wear OS nodes connected")
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "checkConnected failed: ${e.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "checkConnected error: ${e.message}")
        }
    }

    override suspend fun notify(status: CommuteStatus) {
        val ctx = appContext ?: run { Log.w(TAG, "notify skipped: not initialized"); return }
        try {
            val request = PutDataMapRequest.create(DATA_PATH)
            val dataMap = request.dataMap
            buildDataMap(status).forEach { (key, value) ->
                when (value) {
                    is String -> dataMap.putString(key, value)
                    is Long -> dataMap.putLong(key, value)
                }
            }
            dataMap.putLong("sent_at", System.currentTimeMillis())
            val putDataReq = request.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx)
                .putDataItem(putDataReq)
                .addOnSuccessListener { Log.d(TAG, "Wear OS data put success"); onConnected?.invoke() }
                .addOnFailureListener { e -> Log.w(TAG, "Wear OS data put failed: ${e.message}") }
        } catch (e: ApiException) {
            Log.w(TAG, "Wear OS not available: ${e.statusCode} ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "WearOsNotifier.notify error: ${e.message}")
        }
    }
}
