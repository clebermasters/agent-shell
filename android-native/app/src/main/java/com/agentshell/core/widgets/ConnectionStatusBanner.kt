package com.agentshell.core.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.ConnectionStatus
import kotlinx.coroutines.delay

/**
 * Animated status banner that mirrors Flutter's ConnectionStatusBanner.
 *
 * CONNECTED  → green banner visible for 2 s then fades away
 * CONNECTING → orange banner "Connecting…"
 * RECONNECTING → orange banner "Reconnecting…"
 * OFFLINE    → red banner with countdown to next retry
 *
 * Caller provides [connectionStatus]; the composable manages its own
 * visibility state so the banner auto-dismisses after a connected flash.
 */
@Composable
fun ConnectionStatusBanner(
    connectionStatus: ConnectionStatus,
) {
    var visible by remember { mutableStateOf(false) }
    var displayedStatus by remember { mutableStateOf(connectionStatus) }
    var countdown by remember { mutableIntStateOf(5) }

    // React to status changes
    LaunchedEffect(connectionStatus) {
        displayedStatus = connectionStatus
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> {
                visible = true
                // Auto-hide after 2 s on connected
                delay(2_000)
                visible = false
            }
            ConnectionStatus.OFFLINE -> {
                visible = true
                // Tick countdown
                countdown = 5
                while (true) {
                    delay(1_000)
                    countdown = if (countdown > 0) countdown - 1 else 5
                }
            }
            else -> {
                visible = true
                countdown = 5
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top),
        exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top),
    ) {
        val (bgColor, content) = when (displayedStatus) {
            ConnectionStatus.CONNECTED -> Pair(
                Color(0xFF388E3C),
                @Composable {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Connected",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )
            ConnectionStatus.OFFLINE -> Pair(
                Color(0xFFC62828),
                @Composable {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Server unreachable — retrying in ${countdown}s",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )
            ConnectionStatus.RECONNECTING -> Pair(
                Color(0xFFE65100),
                @Composable {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Reconnecting...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )
            ConnectionStatus.CONNECTING -> Pair(
                Color(0xFFE65100),
                @Composable {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Connecting...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(bgColor)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}
