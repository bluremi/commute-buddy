package com.commutebuddy.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusStore.init(this)
        setContent {
            WearApp()
        }
    }
}

private val TierGreen = Color(0xFF4CAF50)
private val TierYellow = Color(0xFFFFD600)
private val TierRed = Color(0xFFF44336)
private val TierGray = Color(0xFF9E9E9E)

private fun tierColor(action: String): Color = when (action.uppercase()) {
    "NORMAL" -> TierGreen
    "MINOR_DELAYS" -> TierYellow
    "REROUTE" -> TierRed
    "STAY_HOME" -> TierRed
    else -> TierGray
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

@Composable
private fun MtaRouteBadge(line: String) {
    val bg = Color(MtaLineColors.lineColor(line))
    val fg = if (MtaLineColors.isLightBackground(line)) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color = bg, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = line,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MtaRouteBadges(affectedRoutes: String) {
    val routes = affectedRoutes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (routes.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        routes.forEach { line -> MtaRouteBadge(line) }
    }
}

@Composable
fun WearApp() {
    val snapshot by StatusStore.flow.collectAsState()
    val listState = rememberScalingLazyListState()
    MaterialTheme {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp)
        ) {
            if (snapshot == null) {
                item {
                    Text(
                        text = "Waiting for data…",
                        color = TierGray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val s = snapshot!!
                item {
                    Text(
                        text = tierLabel(s.action),
                        color = tierColor(s.action),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
                if (s.affectedRoutes.isNotEmpty()) {
                    item {
                        MtaRouteBadges(s.affectedRoutes)
                    }
                }
                item {
                    Text(
                        text = relativeTime(s.timestamp),
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val hint = s.rerouteHint
                if (!hint.isNullOrEmpty()) {
                    item {
                        Text(
                            text = hint,
                            color = tierColor(s.action),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                item {
                    Text(
                        text = s.summary,
                        color = Color(0xFFBDBDBD),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}
