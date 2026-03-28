package com.agentshell.feature.splitscreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.TmuxSession

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
                    // Terminal sessions
                    if (tmuxSessions.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No terminal sessions available", color = MaterialTheme.colorScheme.outline)
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
                    // Chat sessions (tmux + ACP)
                    val hasSessions = tmuxSessions.isNotEmpty() || acpSessions.isNotEmpty()
                    if (!hasSessions) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No chat sessions available", color = MaterialTheme.colorScheme.outline)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            // Tmux chat sessions (window 0)
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
                            // ACP sessions
                            if (acpSessions.isNotEmpty()) {
                                item {
                                    Text(
                                        "AI Chat (Direct)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(acpSessions) { session ->
                                    ListItem(
                                        headlineContent = { Text(session.title.ifEmpty { session.sessionId.take(8) }) },
                                        supportingContent = { Text(session.cwd, fontSize = 12.sp) },
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
