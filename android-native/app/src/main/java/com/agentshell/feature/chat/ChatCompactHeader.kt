package com.agentshell.feature.chat

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dvr
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentshell.core.theme.LocalThemeControls
import com.agentshell.core.theme.resolvedDarkTheme
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus
import kotlinx.coroutines.delay

/** Compact navigation and live status; less frequent actions stay in the menu. */
@Composable
fun ChatCompactHeader(
    title: String,
    activity: AgentActivity,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onNavigateBack: () -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier,
    windowIndex: Int = 0,
    connected: Boolean = true,
    splitEnabled: Boolean = false,
    splitAvailable: Boolean = true,
    onToggleSplit: () -> Unit = {},
    onConfigureSplit: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    onBrowseFiles: (() -> Unit)? = null,
) {
    var showMenu by remember(title) { mutableStateOf(false) }
    val themeControls = LocalThemeControls.current
    val dark = resolvedDarkTheme(themeControls?.mode ?: "system", isSystemInDarkTheme())

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(
                        onClickLabel = if (detailsExpanded) "Hide connection and usage details" else "Show connection and usage details",
                        onClick = onToggleDetails,
                    )
                    .padding(start = 2.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CompactAgentStatus(activity = activity, connected = connected, windowIndex = windowIndex)
                }
                Icon(
                    imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (splitAvailable) {
                IconButton(
                    onClick = onToggleSplit,
                    modifier = Modifier.size(48.dp).semantics {
                        stateDescription = if (splitEnabled) "Split view open" else "Chat only"
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (splitEnabled) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.VerticalSplit,
                            contentDescription = if (splitEnabled) "Close terminal split" else "Split chat and terminal",
                            tint = if (splitEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Chat options", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (detailsExpanded) "Hide connection & usage" else "Connection & usage") },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        onClick = { showMenu = false; onToggleDetails() },
                    )
                    if (splitEnabled && onConfigureSplit != null) {
                        DropdownMenuItem(
                            text = { Text("Split layout…") },
                            leadingIcon = { Icon(Icons.Default.VerticalSplit, contentDescription = null) },
                            onClick = { showMenu = false; onConfigureSplit() },
                        )
                    }
                    if (onOpenTerminal != null) {
                        DropdownMenuItem(
                            text = { Text("Open terminal") },
                            leadingIcon = { Icon(Icons.Default.Dvr, contentDescription = null) },
                            onClick = { showMenu = false; onOpenTerminal() },
                        )
                    }
                    if (onBrowseFiles != null) {
                        DropdownMenuItem(
                            text = { Text("Browse files") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = { showMenu = false; onBrowseFiles() },
                        )
                    }
                    if (themeControls != null) {
                        DropdownMenuItem(
                            text = { Text(if (dark) "Use light theme" else "Use dark theme") },
                            leadingIcon = { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = null) },
                            onClick = { showMenu = false; themeControls.setMode(if (dark) "light" else "dark") },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Clear chat", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onClearChat() },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactAgentStatus(activity: AgentActivity, connected: Boolean, windowIndex: Int) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
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
        AgentActivityStatus.UNKNOWN -> if (connected) "Status unavailable" else "Disconnected"
    }
    val elapsed = if (current.status == AgentActivityStatus.WORKING) current.elapsedSeconds(now) else null
    val estimated = current.confidence == "inferred" || current.confidence == "estimated"
    val status = buildString {
        if (windowIndex > 0) append("W$windowIndex · ")
        append(label)
        elapsed?.let { append(" · ${it / 60}m ${it % 60}s") }
        if (estimated) append(" · estimated")
    }
    val color = when (current.status) {
        AgentActivityStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY, AgentActivityStatus.WAITING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$status. ${current.detail}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (current.status == AgentActivityStatus.WORKING || current.status == AgentActivityStatus.RECENT_ACTIVITY) {
            CircularProgressIndicator(modifier = Modifier.size(9.dp), color = color, strokeWidth = 1.dp)
        } else {
            Box(modifier = Modifier.size(5.dp).background(color, CircleShape))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
