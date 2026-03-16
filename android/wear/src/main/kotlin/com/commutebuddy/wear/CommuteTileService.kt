package com.commutebuddy.wear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
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

class CommuteTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val layout = buildLayout(applicationContext, requestParams.deviceConfiguration)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
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
    deviceParams: DeviceParametersBuilders.DeviceParameters
): LayoutElementBuilders.LayoutElement {
    return materialScope(context, deviceParams) {
        primaryLayout(
            mainSlot = {
                LayoutElementBuilders.Text.Builder()
                    .setText(
                        TypeBuilders.StringProp.Builder("Commute Buddy").build()
                    )
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(
                                ColorBuilders.argb(0xFFFFFFFF.toInt())
                            )
                            .build()
                    )
                    .build()
            }
        )
    }
}
