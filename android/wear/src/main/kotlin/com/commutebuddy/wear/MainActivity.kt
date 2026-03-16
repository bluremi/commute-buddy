package com.commutebuddy.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

private fun relativeTime(timestampMs: Long): String {
    val diffMs = System.currentTimeMillis() - timestampMs
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
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (snapshot == null) {
                Text(
                    text = "Waiting for data…",
                    color = TierGray,
                    fontSize = 14.sp
                )
            } else {
                val s = snapshot!!
                Text(
                    text = tierLabel(s.action),
                    color = tierColor(s.action),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = relativeTime(s.timestamp),
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}
