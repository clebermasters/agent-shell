package com.agentshell.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agentshell.core.widgets.ConnectionStatusBanner
import com.agentshell.data.model.ClaudeUsage
import com.agentshell.feature.system.AlertBanner
import java.time.Instant
import java.time.temporal.ChronoUnit

// Tab indices
private const val TAB_SESSIONS = 0
private const val TAB_CRON = 1
private const val TAB_DOTFILES = 2
private const val TAB_SYSTEM = 3

/**
 * Main app container composable.
 *
 * Mirrors Flutter's HomeScreen:
 * - Persistent bottom NavigationBar with 4 tabs
 * - AlertBanner strip (system resource warnings)
 * - ConnectionStatusBanner (animated WS state)
 * - Alerts bell icon with unread badge in top bar
 * - FAB to open CommandPaletteSheet
 * - Tab content kept alive via remembered composable instances
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTerminal: (sessionName: String) -> Unit,
    onNavigateToChat: (sessionName: String, windowIndex: Int) -> Unit,
    onNavigateToAcpChat: (sessionId: String, cwd: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToHosts: () -> Unit,
    onNavigateToSplitScreen: (layoutId: String?) -> Unit = {},
    sessionsContent: @Composable () -> Unit,
    cronContent: @Composable () -> Unit,
    dotfilesContent: @Composable () -> Unit,
    systemContent: @Composable () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val claudeUsage by viewModel.claudeUsage.collectAsStateWithLifecycle()
    val paletteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPalette by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.currentTabIndex == TAB_SESSIONS,
                    onClick = { viewModel.setTabIndex(TAB_SESSIONS) },
                    icon = {
                        Icon(
                            if (uiState.currentTabIndex == TAB_SESSIONS) Icons.Default.Terminal else Icons.Outlined.Terminal,
                            contentDescription = "Sessions",
                        )
                    },
                    label = { Text("Sessions") },
                )
                NavigationBarItem(
                    selected = uiState.currentTabIndex == TAB_CRON,
                    onClick = { viewModel.setTabIndex(TAB_CRON) },
                    icon = {
                        Icon(
                            if (uiState.currentTabIndex == TAB_CRON) Icons.Default.Schedule else Icons.Outlined.Schedule,
                            contentDescription = "Cron",
                        )
                    },
                    label = { Text("Cron") },
                )
                NavigationBarItem(
                    selected = uiState.currentTabIndex == TAB_DOTFILES,
                    onClick = { viewModel.setTabIndex(TAB_DOTFILES) },
                    icon = {
                        Icon(
                            if (uiState.currentTabIndex == TAB_DOTFILES) Icons.Default.Description else Icons.Outlined.Description,
                            contentDescription = "Dotfiles",
                        )
                    },
                    label = { Text("Dotfiles") },
                )
                NavigationBarItem(
                    selected = uiState.currentTabIndex == TAB_SYSTEM,
                    onClick = { viewModel.setTabIndex(TAB_SYSTEM) },
                    icon = {
                        Icon(
                            if (uiState.currentTabIndex == TAB_SYSTEM) Icons.Default.Monitor else Icons.Outlined.Monitor,
                            contentDescription = "System",
                        )
                    },
                    label = { Text("System") },
                )
            }
        },
        floatingActionButton = {
            if (uiState.currentTabIndex != TAB_CRON && uiState.currentTabIndex != TAB_DOTFILES) {
                FloatingActionButton(
                    onClick = { showPalette = true },
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Command Palette")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Top bar row: alert banner + notifications bell + settings ──
            TopBarRow(
                unreadAlertCount = uiState.unreadAlertCount,
                onAlertsClick = {
                    viewModel.clearUnreadAlerts()
                    onNavigateToAlerts()
                },
                onSplitScreenClick = { onNavigateToSplitScreen(null) },
                onSettingsClick = onNavigateToSettings,
            )

            // ── Alert banner (CPU/RAM/Disk warnings) ──
            AlertBanner(
                stats = uiState.systemStats,
                onTap = { viewModel.setTabIndex(TAB_SYSTEM) },
            )

            // ── Connection status animated banner ──
            ConnectionStatusBanner(connectionStatus = uiState.connectionStatus)

            // ── Claude usage banner ──
            claudeUsage?.let { usage -> ClaudeUsageBanner(usage) }

            // ── Tab content (preserved – uses Box with visibility to keep state) ──
            Box(modifier = Modifier.fillMaxSize()) {
                // Using "show only the active one" pattern so that tab state
                // is preserved across switches (same as Flutter's IndexedStack).
                TabContent(visible = uiState.currentTabIndex == TAB_SESSIONS) { sessionsContent() }
                TabContent(visible = uiState.currentTabIndex == TAB_CRON) { cronContent() }
                TabContent(visible = uiState.currentTabIndex == TAB_DOTFILES) { dotfilesContent() }
                TabContent(visible = uiState.currentTabIndex == TAB_SYSTEM) { systemContent() }
            }
        }
    }

    // ── Command Palette Sheet ──
    if (showPalette) {
        CommandPaletteSheet(
            tmuxSessions = uiState.tmuxSessions,
            acpSessions = uiState.acpSessions,
            cronJobs = uiState.cronJobs,
            dotFiles = uiState.dotFiles,
            onNavigateToTab = { index ->
                viewModel.setTabIndex(index)
                showPalette = false
            },
            onNavigateToTerminal = { name ->
                showPalette = false
                onNavigateToTerminal(name)
            },
            onNavigateToAcpChat = { id, cwd ->
                showPalette = false
                onNavigateToAcpChat(id, cwd)
            },
            onDismiss = { showPalette = false },
            sheetState = paletteSheetState,
        )
    }
}

@Composable
private fun TopBarRow(
    unreadAlertCount: Int,
    onAlertsClick: () -> Unit,
    onSplitScreenClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = "AgentShell",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )

        IconButton(onClick = onSplitScreenClick, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Dashboard, contentDescription = "Split Screen", modifier = Modifier.size(20.dp))
        }

        BadgedBox(
            badge = {
                if (unreadAlertCount > 0) {
                    Badge { Text(if (unreadAlertCount > 99) "99+" else "$unreadAlertCount") }
                }
            },
        ) {
            IconButton(onClick = onAlertsClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (unreadAlertCount > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                    contentDescription = "Alerts",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Wraps a tab's content so it is always in composition (preserving state)
 * but only visible when [visible] is true — analogous to Flutter's IndexedStack.
 */
@Composable
private fun TabContent(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    if (visible) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

// ── Claude Usage Banner ──────────────────────────────────────────────────────

private fun usageColor(pct: Double): Color = when {
    pct >= 80 -> Color(0xFFEF4444) // red
    pct >= 50 -> Color(0xFFF59E0B) // amber
    else -> Color(0xFF10B981)       // green
}

private fun formatResetCountdown(resetsAt: String): String {
    return try {
        val reset = Instant.parse(resetsAt)
        val now = Instant.now()
        val mins = ChronoUnit.MINUTES.between(now, reset)
        if (mins <= 0) "now"
        else if (mins < 60) "${mins}m"
        else "${mins / 60}h ${mins % 60}m"
    } catch (_: Exception) { "" }
}

@Composable
private fun ClaudeUsageBanner(usage: ClaudeUsage) {
    // Hide banner entirely when there's an error (not logged in, no credentials, etc.)
    if (usage.error != null) return
    val fh = usage.fiveHour
    val sd = usage.sevenDay
    if (fh == null && sd == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: utilization percentages
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            fh?.let {
                val color = usageColor(it.utilization)
                Text(
                    text = "5h ${it.utilization.toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
            }
            sd?.let {
                val color = usageColor(it.utilization)
                Text(
                    text = "7d ${it.utilization.toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
            }
        }
        // Right: reset countdown
        fh?.let {
            val countdown = formatResetCountdown(it.resetsAt)
            if (countdown.isNotEmpty()) {
                Text(
                    text = "resets $countdown",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Progress bar for 5-hour usage
    fh?.let {
        LinearProgressIndicator(
            progress = { (it.utilization / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = usageColor(it.utilization),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
