package com.agentshell.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.TmuxSession

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onNavigateToTerminal: (sessionName: String) -> Unit,
    onNavigateToChat: (sessionName: String, windowIndex: Int) -> Unit,
    onNavigateToAcpChat: (sessionId: String, cwd: String) -> Unit,
    onNavigateToHosts: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    // Listen for new ACP session events → navigate immediately
    LaunchedEffect(Unit) {
        viewModel.newAcpSession.collect { event ->
            onNavigateToAcpChat(event.sessionId, event.cwd)
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${uiState.selectedSessionIds.size} selected") },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.deleteSelectedSessions()
                            },
                            enabled = uiState.selectedSessionIds.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = if (uiState.selectedSessionIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Sessions") },
                    actions = {
                        IconButton(onClick = onNavigateToHosts) {
                            Icon(Icons.Default.Edit, contentDescription = "Servers")
                        }
                        IconButton(onClick = { viewModel.requestSessions() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New Session")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Session")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Tabs: Tmux / ACP
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Terminal") },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Direct (ACP)") },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.requestSessions() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (selectedTabIndex) {
                    0 -> TmuxSessionList(
                        sessions = uiState.tmuxSessions,
                        isLoading = uiState.isLoading,
                        onTap = { session -> onNavigateToTerminal(session.name) },
                        onChat = { session -> onNavigateToChat(session.name, 0) },
                        onKill = { session ->
                            viewModel.killSession(session.name)
                        },
                    )
                    1 -> AcpSessionList(
                        sessions = uiState.acpSessions,
                        isLoading = uiState.isLoading,
                        isSelectionMode = uiState.isSelectionMode,
                        selectedIds = uiState.selectedSessionIds,
                        onTap = { session ->
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelection(session.sessionId)
                            } else {
                                onNavigateToAcpChat(session.sessionId, session.cwd)
                            }
                        },
                        onLongPress = { session -> viewModel.enterSelectionMode(session.sessionId) },
                        onDelete = { session -> viewModel.deleteAcpSession(session.sessionId) },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateSessionDialog(
            onCreateTmux = { name ->
                viewModel.createSession(name)
                showCreateDialog = false
            },
            onCreateAcp = { cwd ->
                viewModel.createAcpSession(cwd)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun TmuxSessionList(
    sessions: List<TmuxSession>,
    isLoading: Boolean,
    onTap: (TmuxSession) -> Unit,
    onChat: (TmuxSession) -> Unit,
    onKill: (TmuxSession) -> Unit,
) {
    if (isLoading && sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (sessions.isEmpty()) {
        EmptySessionsMessage(
            icon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(64.dp)) },
            message = "No terminal sessions",
            hint = "Create a new tmux session to get started",
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sessions, key = { it.name }) { session ->
            TmuxSessionCard(
                session = session,
                onTap = { onTap(session) },
                onChat = { onChat(session) },
                onKill = { onKill(session) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TmuxSessionCard(
    session: TmuxSession,
    onTap: () -> Unit,
    onChat: () -> Unit,
    onKill: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showKillDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onTap, onLongClick = { showMenu = true }),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = if (session.attached) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(session.name, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    "${session.windows} window${if (session.windows != 1) "s" else ""}" +
                        if (session.attached) " • Attached" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open Terminal") },
                            leadingIcon = { Icon(Icons.Default.Terminal, null) },
                            onClick = { showMenu = false; onTap() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open Chat") },
                            leadingIcon = { Icon(Icons.Outlined.Chat, null) },
                            onClick = { showMenu = false; onChat() },
                        )
                        DropdownMenuItem(
                            text = { Text("Kill Session", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; showKillDialog = true },
                        )
                    }
                }
            },
        )
    }

    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            title = { Text("Kill Session") },
            text = { Text("Are you sure you want to kill \"${session.name}\"?") },
            confirmButton = {
                TextButton(onClick = { showKillDialog = false; onKill() }) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showKillDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AcpSessionList(
    sessions: List<AcpSession>,
    isLoading: Boolean,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onTap: (AcpSession) -> Unit,
    onLongPress: (AcpSession) -> Unit,
    onDelete: (AcpSession) -> Unit,
) {
    if (isLoading && sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (sessions.isEmpty()) {
        EmptySessionsMessage(
            icon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(64.dp)) },
            message = "No direct sessions",
            hint = "Create a new ACP session to get started",
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sessions, key = { it.sessionId }) { session ->
            AcpSessionCard(
                session = session,
                isSelectionMode = isSelectionMode,
                isSelected = selectedIds.contains(session.sessionId),
                onTap = { onTap(session) },
                onLongPress = { onLongPress(session) },
                onDelete = { onDelete(session) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AcpSessionCard(
    session: AcpSession,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val displayTitle = session.title.ifBlank { session.cwd.substringAfterLast('/') }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        ListItem(
            leadingContent = {
                if (isSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onTap() })
                } else {
                    Icon(Icons.Default.SmartToy, contentDescription = null)
                }
            },
            headlineContent = { Text(displayTitle, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(session.cwd, style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                if (!isSelectionMode) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showMenu = false; showDeleteDialog = true },
                            )
                        }
                    }
                }
            },
        )
    }

    if (showDeleteDialog) {
        val dirName = session.cwd.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ACP Session") },
            text = { Text("Delete session in \"$dirName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptySessionsMessage(
    icon: @Composable () -> Unit,
    message: String,
    hint: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSessionDialog(
    onCreateTmux: (name: String) -> Unit,
    onCreateAcp: (cwd: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var selectedBackend by rememberSaveable { mutableStateOf("tmux") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(if (selectedBackend == "tmux") "Session Name" else "Working Directory") },
                    placeholder = {
                        Text(if (selectedBackend == "tmux") "e.g., my-session" else "e.g., /home/user/project")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Backend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedBackend == "tmux",
                        onClick = { selectedBackend = "tmux" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Terminal, null) },
                    ) { Text("Terminal") }
                    SegmentedButton(
                        selected = selectedBackend == "acp",
                        onClick = { selectedBackend = "acp" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.SmartToy, null) },
                    ) { Text("Direct") }
                }

                Text(
                    text = if (selectedBackend == "tmux")
                        "Terminal mode: Full terminal with tmux"
                    else
                        "Direct mode: Chat-focused (ACP)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        if (selectedBackend == "tmux") onCreateTmux(inputText.trim())
                        else onCreateAcp(inputText.trim())
                    }
                },
                enabled = inputText.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
