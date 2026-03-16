package com.commutebuddy.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
fun WearApp() {
    val snapshot by StatusStore.flow.collectAsState()
    val listState = rememberScalingLazyListState()
    MaterialTheme {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState
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
