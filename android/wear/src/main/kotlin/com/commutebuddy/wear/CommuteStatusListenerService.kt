package com.commutebuddy.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class CommuteStatusListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "CommuteStatusListener"
        private const val DATA_PATH = "/commute-status"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == DATA_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val snapshot = CommuteStatusSnapshot(
                    action = dataMap.getString("action") ?: "NORMAL",
                    summary = dataMap.getString("summary") ?: "",
                    affectedRoutes = dataMap.getString("affected_routes") ?: "",
                    rerouteHint = if (dataMap.containsKey("reroute_hint")) dataMap.getString("reroute_hint") else null,
                    timestamp = dataMap.getLong("timestamp")
                )
                Log.d(TAG, "Received commute status: action=${snapshot.action}, timestamp=${snapshot.timestamp}")
                StatusStore.save(applicationContext, snapshot)
                TileService.getUpdater(applicationContext)
                    .requestUpdate(CommuteTileService::class.java)
            }
        }
        dataEvents.release()
    }
}
