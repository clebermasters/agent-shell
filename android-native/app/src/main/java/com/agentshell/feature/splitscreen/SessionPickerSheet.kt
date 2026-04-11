package com.agentshell.feature.splitscreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.core.util.timeAgo
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.TmuxSession
import java.time.Instant
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPickerSheet(
    tmuxSessions: List<TmuxSession>,
    acpSessions: List<AcpSession>,
    onSelectTerminal: (TmuxSession) -> Unit,
    onSelectChat: (TmuxSession, Int) -> Unit,
    onSelectAcpChat: (AcpSession) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                "Assign Session",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Terminal", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Chat", fontSize = 13.sp)
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    if (tmuxSessions.isEmpty()) {
                        Box {
                            Text(
                                "No terminal sessions available",
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(tmuxSessions) { session ->
                                ListItem(
                                    headlineContent = { Text(session.name) },
                                    supportingContent = { Text("${session.windows} window${if (session.windows != 1) "s" else ""}", fontSize = 12.sp) },
                                    leadingContent = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                    trailingContent = {
                                        if (session.attached) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text("active", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { onSelectTerminal(session); onDismiss() },
                                )
                            }
                        }
                    }
                }
                1 -> {
                    val hasSessions = tmuxSessions.isNotEmpty() || acpSessions.isNotEmpty()
                    if (!hasSessions) {
                        Box {
                            Text(
                                "No chat sessions available",
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            if (tmuxSessions.isNotEmpty()) {
                                item {
                                    Text(
                                        "Terminal Chat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(tmuxSessions) { session ->
                                    ListItem(
                                        headlineContent = { Text(session.name) },
                                        supportingContent = { Text("tmux chat", fontSize = 12.sp) },
                                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                        modifier = Modifier.clickable { onSelectChat(session, 0); onDismiss() },
                                    )
                                }
                            }

                            if (acpSessions.isNotEmpty()) {
                                val groupedAcpSessions = acpSessions
                                    .sortedByDescending { parseAcpUpdatedAtEpoch(it) }
                                    .groupBy { acpPathGroupKey(it.cwd) }
                                    .entries
                                    .map { (pathGroup, pathSessions) ->
                                        pathGroup to pathSessions.sortedByDescending { parseAcpUpdatedAtEpoch(it) }
                                    }
                                    .sortedByDescending { (_, pathSessions) ->
                                        pathSessions.maxOfOrNull { parseAcpUpdatedAtEpoch(it) } ?: 0L
                                    }

                                groupedAcpSessions.forEach { (pathGroup, pathSessions) ->
                                    item {
                                        AcpPathHeader(
                                            label = acpPathGroupLabel(pathGroup),
                                            count = pathSessions.size,
                                            color = acpPathTone(pathGroup),
                                        )
                                    }
                                    val providerGroups = pathSessions
                                        .groupBy { acpProviderLabel(it) }
                                        .toSortedMap { left, right ->
                                            acpProviderSortIndex(left).compareTo(acpProviderSortIndex(right))
                                        }
                                    providerGroups.forEach { (providerLabel, providerSessions) ->
                                        item {
                                            AcpProviderPillRow(
                                                label = providerLabel,
                                                count = providerSessions.size,
                                            )
                                        }
                                        items(providerSessions, key = { it.sessionId }) { session ->
                                            val compactPath = acpCompactPath(session.cwd)
                                            ListItem(
                                                headlineContent = {
                                                    Text(session.title.ifEmpty { session.sessionId.substringAfterLast(":", session.sessionId) })
                                                },
                                                supportingContent = {
                                                    Column {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            AcpProviderPill(label = providerLabel)
                                                            Text(
                                                                compactPath,
                                                                fontSize = 12.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                        Text(
                                                            acpUpdatedAtLabel(session.updatedAt),
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.outline,
                                                        )
                                                    }
                                                },
                                                leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                                                modifier = Modifier.clickable { onSelectAcpChat(session); onDismiss() },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcpPathHeader(
    label: String,
    count: Int,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = color.copy(alpha = 0.16f),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(" ", fontSize = 1.sp, modifier = Modifier.padding(6.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun AcpProviderPillRow(
    label: String,
    count: Int,
) {
    val (bg, fg) = acpProviderTone(label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = bg,
            contentColor = fg,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun AcpProviderPill(
    label: String,
) {
    val (bg, fg) = acpProviderTone(label)
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun acpProviderLabel(session: AcpSession): String {
    val provider = session.provider?.lowercase()?.trim()
    return when (provider) {
        "codex" -> "Codex"
        "opencode", "acp" -> "OpenCode"
        else -> {
            val prefix = session.sessionId.substringBefore(":").lowercase()
            when (prefix) {
                "codex" -> "Codex"
                "opencode", "acp" -> "OpenCode"
                else -> "Direct"
            }
        }
    }
}

private fun acpProviderSortIndex(label: String): Int = when (label) {
    "Codex" -> 0
    "OpenCode" -> 1
    else -> 2
}

@Composable
private fun acpProviderTone(label: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (label) {
        "Codex" -> Pair(scheme.primary.copy(alpha = 0.16f), scheme.primary)
        "OpenCode" -> Pair(scheme.tertiary.copy(alpha = 0.16f), scheme.tertiary)
        else -> Pair(scheme.secondary.copy(alpha = 0.14f), scheme.secondary)
    }
}

private fun acpPathGroupKey(cwd: String): String {
    val trimmed = cwd.trim()
    if (trimmed.isBlank()) return "Unknown directory"
    val normalized = trimmed.trimEnd('/')
    if (normalized.isBlank()) return "/"
    if (normalized == "/") return "/"
    return normalized
}

private fun acpPathGroupLabel(cwd: String): String {
    if (cwd.isBlank()) return "Unknown directory"
    return acpCompactPath(cwd)
}

private fun acpPathTone(pathLabel: String): Color {
    val palette = listOf(
        Color(0xFF2563EB),
        Color(0xFF0EA5E9),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFF8B5CF6),
        Color(0xFFEF4444),
        Color(0xFF06B6D4),
    )
    val idx = (pathLabel.hashCode().absoluteValue) % palette.size
    return palette[idx]
}

private fun acpCompactPath(cwd: String): String {
    if (cwd.isBlank()) return "Unknown directory"
    if (cwd == "/") return "/"
    val normalized = if (cwd.endsWith("/")) cwd.dropLast(1) else cwd
    val parts = normalized.split("/").filter { it.isNotBlank() }
    return if (parts.size <= 2) "/${parts.joinToString("/")}" else "/.../${parts.takeLast(2).joinToString("/")}"
}

private fun parseAcpUpdatedAtEpoch(session: AcpSession): Long {
    return runCatching { Instant.parse(session.updatedAt).toEpochMilli() }.getOrDefault(0L)
}

private fun acpUpdatedAtLabel(updatedAt: String): String {
    if (updatedAt.isBlank()) return "Last activity: unknown"
    val epochMs = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrNull() ?: return "Last activity: unknown"
    return "Last activity: ${epochMs.timeAgo()}"
}
