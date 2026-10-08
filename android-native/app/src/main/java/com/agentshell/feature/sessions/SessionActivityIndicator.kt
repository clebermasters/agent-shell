package com.agentshell.feature.sessions

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus

internal fun activityPriority(activity: AgentActivity): Int = when (activity.status) {
    AgentActivityStatus.WORKING -> 5
    AgentActivityStatus.WAITING -> 4
    AgentActivityStatus.RECENT_ACTIVITY -> 3
    AgentActivityStatus.FAILED -> 2
    AgentActivityStatus.IDLE -> 1
    AgentActivityStatus.UNKNOWN -> 0
}

@Composable
private fun activityColor(activity: AgentActivity?): Color = when (activity?.status) {
    AgentActivityStatus.WORKING -> MaterialTheme.colorScheme.primary
    AgentActivityStatus.RECENT_ACTIVITY -> MaterialTheme.colorScheme.tertiary
    AgentActivityStatus.WAITING -> Color(0xFFD5A84D)
    AgentActivityStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentActivityStatus.IDLE -> Color(0xFF65B98A)
    else -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun sessionActivityBorder(activity: AgentActivity?): Modifier {
    val active = activity?.status in setOf(AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY)
    val color = activityColor(activity)
    if (!active) return Modifier
    val transition = rememberInfiniteTransition(label = "sessionPulse")
    val alpha = transition.animateFloat(0.12f, 0.36f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "borderPulse")
    return Modifier.drawWithContent {
        drawContent()
        drawRoundRect(color.copy(alpha = alpha.value), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
}

@Composable
internal fun SessionActivityEmblem(icon: ImageVector, activity: AgentActivity?, baseTint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val active = activity?.status in setOf(AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY)
    val color = if (activity == null) baseTint else activityColor(activity)
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        if (active) {
            val animation = rememberInfiniteTransition(label = "agentOrbit")
            val rotation = animation.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "orbit")
            val pulse = animation.animateFloat(0.06f, 0.17f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "halo")
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color.copy(alpha = pulse.value))
                drawCircle(color.copy(alpha = 0.16f), style = Stroke(1.5.dp.toPx()))
                drawArc(color, rotation.value - 90, 95f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color.copy(alpha = 0.35f), rotation.value + 90, 35f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        } else {
            Canvas(Modifier.fillMaxSize()) { drawCircle(color.copy(alpha = 0.08f)) }
        }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
    }
}

@Composable
internal fun SessionActivityBadge(activity: AgentActivity?, now: Long) {
    if (activity == null) return
    val color = activityColor(activity)
    val label = when (activity.status) {
        AgentActivityStatus.WORKING -> "Working"
        AgentActivityStatus.RECENT_ACTIVITY -> "Recent activity"
        AgentActivityStatus.WAITING -> "Waiting for you"
        AgentActivityStatus.FAILED -> "Failed"
        AgentActivityStatus.IDLE -> if (activity.completionReason == "completed" && activity.finishedAt != null && activity.observedAt - activity.finishedAt < 60_000) "Finished" else "Idle"
        AgentActivityStatus.UNKNOWN -> if (activity.quietAt != null && activity.source.startsWith("tmux")) "Quiet" else "Status unavailable"
    }
    val elapsed = if (activity.status == AgentActivityStatus.WORKING) activity.elapsedSeconds(now) else null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        elapsed?.let { Text(if (it >= 3600) "${it / 3600}h ${(it % 3600) / 60}m" else "${it / 60}m ${it % 60}s", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1) }
        if (activity.confidence in setOf("inferred", "estimated") && activity.status in setOf(AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY)) {
            Text("Estimated", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}
