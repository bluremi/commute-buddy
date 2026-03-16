package com.commutebuddy.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
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

private const val BADGE_SIZE_DP = 18f
private const val BADGE_FONT_SP = 9f
private const val BADGE_SPACING_DP = 3f
private const val BADGE_ROW_SPACING_DP = 2f
private const val BADGES_PER_ROW = 4
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_BLACK = 0xFF000000.toInt()

private fun buildBadge(line: String): LayoutElementBuilders.LayoutElement {
    val bgColor = MtaLineColors.lineColor(line)
    val textColor = if (MtaLineColors.isLightBackground(line)) COLOR_BLACK else COLOR_WHITE
    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.dp(BADGE_SIZE_DP))
        .setHeight(DimensionBuilders.dp(BADGE_SIZE_DP))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(bgColor))
                        .setCorner(
                            ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(BADGE_SIZE_DP / 2f))
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(TypeBuilders.StringProp.Builder(line).build())
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setColor(ColorBuilders.argb(textColor))
                        .setWeight(
                            LayoutElementBuilders.FontWeightProp.Builder()
                                .setValue(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                .build()
                        )
                        .setSize(
                            DimensionBuilders.SpProp.Builder()
                                .setValue(BADGE_FONT_SP)
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()
}

/** Renders badges in rows of [BADGES_PER_ROW], wrapping into a Column if needed. */
private fun buildBadgeRows(affectedRoutes: String): LayoutElementBuilders.LayoutElement {
    val lines = affectedRoutes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .build()
    }
    val col = LayoutElementBuilders.Column.Builder()
        .setWidth(DimensionBuilders.wrap())
        .setHeight(DimensionBuilders.wrap())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    for ((rowIdx, chunk) in lines.chunked(BADGES_PER_ROW).withIndex()) {
        if (rowIdx > 0) {
            col.addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(BADGE_ROW_SPACING_DP))
                    .build()
            )
        }
        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        for ((idx, line) in chunk.withIndex()) {
            if (idx > 0) {
                row.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.dp(BADGE_SPACING_DP))
                        .build()
                )
            }
            row.addContent(buildBadge(line))
        }
        col.addContent(row.build())
    }
    return col.build()
}

private fun buildClickable(context: Context): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
        .setId("open_main")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setClassName("com.commutebuddy.wear.MainActivity")
                        .setPackageName(context.packageName)
                        .build()
                )
                .build()
        )
        .build()

private fun buildLayout(
    context: Context,
    deviceParams: DeviceParametersBuilders.DeviceParameters,
    snapshot: CommuteStatusSnapshot?
): LayoutElementBuilders.LayoutElement {
    val clickable = buildClickable(context)
    val inner = materialScope(context, deviceParams) {
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
            val bottomText: String
            val bottomColor: Int
            if (!snapshot.rerouteHint.isNullOrEmpty()) {
                bottomText = snapshot.rerouteHint
                bottomColor = tierColorArgb(snapshot.action)
            } else {
                bottomText = snapshot.summary
                bottomColor = 0xFFBDBDBD.toInt() // light gray
            }
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
                    LayoutElementBuilders.Text.Builder()
                        .setText(TypeBuilders.StringProp.Builder(bottomText).build())
                        .setMaxLines(TypeBuilders.Int32Prop.Builder().setValue(3).build())
                        .setOverflow(
                            LayoutElementBuilders.TextOverflowProp.Builder()
                                .setValue(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                                .build()
                        )
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setColor(ColorBuilders.argb(bottomColor))
                                .setSize(
                                    DimensionBuilders.SpProp.Builder()
                                        .setValue(12f)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                },
                bottomSlot = {
                    buildBadgeRows(snapshot.affectedRoutes)
                }
            )
        }
    }
    // Wrap in a full-screen Box so the entire tile is tappable
    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickable)
                .build()
        )
        .addContent(inner)
        .build()
}
