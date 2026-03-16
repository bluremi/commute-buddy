package com.commutebuddy.wear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val COLOR_GREEN = 0xFF4CAF50.toInt()
private const val COLOR_YELLOW = 0xFFFFD600.toInt()
private const val COLOR_RED = 0xFFF44336.toInt()
private const val COLOR_GRAY = 0xFF9E9E9E.toInt()

private fun tierColorArgb(action: String): Int = when (action.uppercase()) {
    "NORMAL" -> COLOR_GREEN
    "MINOR_DELAYS" -> COLOR_YELLOW
    "REROUTE", "STAY_HOME" -> COLOR_RED
    else -> COLOR_GRAY
}

private fun tierLabel(action: String): String = when (action.uppercase()) {
    "NORMAL" -> "Normal"
    "MINOR_DELAYS" -> "Minor Delays"
    "REROUTE" -> "Reroute"
    "STAY_HOME" -> "Stay Home"
    else -> action
}

private fun relativeTime(timestampSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - (timestampSeconds * 1000)
    val diffMin = (diffMs / 60_000).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "just now"
        diffMin < 60 -> "$diffMin min ago"
        else -> "${diffMin / 60} hr ago"
    }
}

class CommuteTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val FRESHNESS_INTERVAL_MS = 10 * 60 * 1000L
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val snapshot = StatusStore.load(applicationContext)
        val layout = buildLayout(applicationContext, requestParams.deviceConfiguration, snapshot)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }
}

private fun buildLayout(
    context: Context,
    deviceParams: DeviceParametersBuilders.DeviceParameters,
    snapshot: CommuteStatusSnapshot?
): LayoutElementBuilders.LayoutElement {
    return materialScope(context, deviceParams) {
        if (snapshot == null) {
            primaryLayout(
                mainSlot = {
                    LayoutElementBuilders.Text.Builder()
                        .setText(TypeBuilders.StringProp.Builder("Waiting for data…").build())
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setColor(ColorBuilders.argb(COLOR_GRAY))
                                .build()
                        )
                        .build()
                }
            )
        } else {
            primaryLayout(
                titleSlot = {
                    LayoutElementBuilders.Column.Builder()
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(TypeBuilders.StringProp.Builder(tierLabel(snapshot.action)).build())
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(tierColorArgb(snapshot.action)))
                                        .setWeight(
                                            LayoutElementBuilders.FontWeightProp.Builder()
                                                .setValue(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(TypeBuilders.StringProp.Builder(relativeTime(snapshot.timestamp)).build())
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(COLOR_GRAY))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                },
                mainSlot = {
                    LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setHeight(DimensionBuilders.wrap())
                        .build()
                }
            )
        }
    }
}
