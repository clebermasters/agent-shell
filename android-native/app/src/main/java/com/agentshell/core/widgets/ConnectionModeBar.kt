package com.agentshell.core.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.core.power.ConnectionMode
import com.agentshell.core.power.ConnectionModeState
import com.agentshell.core.power.ConnectionModeViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One tap changes mode; the timer menu is available without leaving the conversation. */
@Composable
fun ConnectionModeBar(viewModel: ConnectionModeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTimer by remember { mutableStateOf(false) }
    val live = state.mode == ConnectionMode.LIVE
    val detail = when {
        state.saveError != null -> "Could not save mode"
        state.systemPaused -> "Tap to resume"
        live && state.liveUntilEpochMillis != null -> "Saver at ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(state.liveUntilEpochMillis!!).atZone(ZoneId.systemDefault()))}"
        live -> "No timer"
        state.timerMinutes > 0 -> "Next Live: ${timerLabel(state.timerMinutes)}"
        else -> "Updates while open"
    }
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (live) Icons.Default.Bolt else Icons.Default.BatterySaver,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).clickable(enabled = state.systemPaused, onClick = viewModel::resume)) {
                Text(if (state.systemPaused) "Live paused" else if (live) "Live" else "Battery Saver",
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = state.ready, onClick = {
                viewModel.selectMode(if (live) ConnectionMode.BATTERY_SAVER else ConnectionMode.LIVE)
            }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (live) "Battery Saver" else "Go Live")
            }
            Box {
                IconButton(enabled = state.ready, onClick = { showTimer = true }) {
                    Icon(Icons.Default.Timer, contentDescription = "Set timer to return to Battery Saver")
                }
                DropdownMenu(expanded = showTimer, onDismissRequest = { showTimer = false }) {
                    for (minutes in ConnectionModeState.TIMER_PRESETS_MINUTES) {
                        DropdownMenuItem(text = { Text(timerLabel(minutes)) },
                            leadingIcon = {
                                if (state.timerMinutes == minutes) Icon(Icons.Default.Check, contentDescription = "Selected")
                                else Spacer(Modifier.size(24.dp))
                            }, onClick = { showTimer = false; viewModel.selectTimer(minutes) })
                    }
                }
            }
        }
    }
}

internal fun timerLabel(minutes: Int): String = when {
    minutes == 0 -> "No timer"
    minutes == 1 -> "1 minute"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}
