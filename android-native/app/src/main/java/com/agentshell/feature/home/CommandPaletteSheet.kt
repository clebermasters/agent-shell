package com.agentshell.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.CronJob
import com.agentshell.data.model.DotFile
import com.agentshell.data.model.TmuxSession

enum class PaletteItemType { NAV, SESSION, ACP_SESSION, CRON_JOB, DOTFILE }

data class PaletteItem(
    val id: String,
    val type: PaletteItemType,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    // Payload for navigation
    val sessionName: String? = null,
    val sessionId: String? = null,
    val cwd: String? = null,
    val tabIndex: Int? = null,
)

/**
 * Command palette as a ModalBottomSheet.
 *
 * Mirrors Flutter's CommandPalette widget: search bar + grouped results list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    tmuxSessions: List<TmuxSession>,
    acpSessions: List<AcpSession>,
    cronJobs: List<CronJob>,
    dotFiles: List<DotFile>,
    onNavigateToTab: (index: Int) -> Unit,
    onNavigateToTerminal: (sessionName: String) -> Unit,
    onNavigateToAcpChat: (sessionId: String, cwd: String) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Build index of all navigable items
    val allItems: List<PaletteItem> = remember(tmuxSessions, acpSessions, cronJobs, dotFiles) {
        val items = mutableListOf<PaletteItem>()

        // Nav items
        items += PaletteItem("nav_sessions", PaletteItemType.NAV, "Sessions", "Terminal sessions tab", Icons.Default.Terminal, tabIndex = 0)
        items += PaletteItem("nav_cron", PaletteItemType.NAV, "Cron", "Scheduled jobs tab", Icons.Default.Schedule, tabIndex = 1)
        items += PaletteItem("nav_dotfiles", PaletteItemType.NAV, "Dotfiles", "Configuration files tab", Icons.Default.Folder, tabIndex = 2)
        items += PaletteItem("nav_system", PaletteItemType.NAV, "System", "System stats tab", Icons.Default.Monitor, tabIndex = 3)

        // Tmux sessions
        tmuxSessions.forEach { s ->
            items += PaletteItem(
                id = "session_${s.name}",
                type = PaletteItemType.SESSION,
                title = s.name,
                subtitle = "${s.windows} window${if (s.windows != 1) "s" else ""}",
                icon = Icons.Default.Terminal,
                sessionName = s.name,
            )
        }

        // ACP sessions
        acpSessions.forEach { s ->
            val title = s.title.ifBlank { s.cwd.substringAfterLast('/') }
            items += PaletteItem(
                id = "acp_${s.sessionId}",
                type = PaletteItemType.ACP_SESSION,
                title = title,
                subtitle = s.cwd,
                icon = Icons.Default.SmartToy,
                sessionId = s.sessionId,
                cwd = s.cwd,
            )
        }

        // Cron jobs
        cronJobs.forEach { job ->
            items += PaletteItem(
                id = "cron_${job.id}",
                type = PaletteItemType.CRON_JOB,
                title = job.name,
                subtitle = job.schedule,
                icon = Icons.Default.Schedule,
                tabIndex = 1,
            )
        }

        // Dotfiles
        dotFiles.forEach { f ->
            items += PaletteItem(
                id = "dot_${f.path}",
                type = PaletteItemType.DOTFILE,
                title = f.name,
                subtitle = f.path,
                icon = Icons.Default.Folder,
                tabIndex = 2,
            )
        }

        items
    }

    val filtered: List<PaletteItem> = remember(query, allItems) {
        if (query.isBlank()) allItems
        else {
            val q = query.lowercase()
            allItems.filter {
                it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search sessions, cron jobs, dotfiles…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            LazyColumn {
                items(filtered, key = { it.id }) { item ->
                    PaletteResultItem(
                        item = item,
                        onClick = {
                            onDismiss()
                            when (item.type) {
                                PaletteItemType.NAV ->
                                    item.tabIndex?.let { onNavigateToTab(it) }
                                PaletteItemType.SESSION ->
                                    item.sessionName?.let { onNavigateToTerminal(it) }
                                PaletteItemType.ACP_SESSION ->
                                    if (item.sessionId != null && item.cwd != null)
                                        onNavigateToAcpChat(item.sessionId, item.cwd)
                                PaletteItemType.CRON_JOB ->
                                    item.tabIndex?.let { onNavigateToTab(it) }
                                PaletteItemType.DOTFILE ->
                                    item.tabIndex?.let { onNavigateToTab(it) }
                            }
                        },
                    )
                }
            }
        }
    }

    // Auto-focus search on open
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun PaletteResultItem(
    item: PaletteItem,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        headlineContent = { Text(item.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
