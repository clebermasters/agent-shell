package com.agentshell.feature.chat

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus
import kotlinx.coroutines.delay

@Composable
fun AgentActivityBar(activity: AgentActivity, connected: Boolean = true) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val current = when {
        !connected -> activity.unknown("Connection unavailable")
        activity.expired(now) -> activity.unknown("Live status is unavailable")
        activity.receivedAt == 0L && now - openedAt > 5000 -> activity.unknown("Live status is unavailable")
        else -> activity
    }
    val label = when (current.status) {
        AgentActivityStatus.WORKING -> "Working"
        AgentActivityStatus.RECENT_ACTIVITY -> "Recent activity"
        AgentActivityStatus.WAITING -> "Waiting for you"
        AgentActivityStatus.IDLE -> "Idle"
        AgentActivityStatus.FAILED -> "Failed"
        AgentActivityStatus.UNKNOWN -> "Unknown"
    }
    val elapsed = if (current.status == AgentActivityStatus.WORKING) current.elapsedSeconds(now) else null
    val color = when (current.status) {
        AgentActivityStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY, AgentActivityStatus.WAITING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current.status in setOf(AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
            } else {
                val icon = when (current.status) {
                    AgentActivityStatus.WAITING -> Icons.Default.PauseCircleOutline
                    AgentActivityStatus.IDLE -> Icons.Default.CheckCircleOutline
                    AgentActivityStatus.FAILED -> Icons.Default.ErrorOutline
                    else -> Icons.Default.HelpOutline
                }
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label + (elapsed?.let { " · ${it / 60}m ${it % 60}s" } ?: ""), style = MaterialTheme.typography.labelMedium, color = color)
                Text(current.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current.confidence == "inferred" || current.confidence == "estimated") {
                Text("Estimated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
