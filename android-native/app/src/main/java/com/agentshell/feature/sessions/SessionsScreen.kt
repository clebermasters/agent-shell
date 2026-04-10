package com.agentshell.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.FavoriteSession
import com.agentshell.data.model.SessionTag
import com.agentshell.data.model.TmuxSession

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onNavigateToTerminal: (sessionName: String) -> Unit,
    onNavigateToChat: (sessionName: String, windowIndex: Int) -> Unit,
    onNavigateToAcpChat: (sessionId: String, cwd: String) -> Unit,
    onNavigateToHosts: () -> Unit,
    onNavigateToGit: (sessionName: String, path: String, isAcp: Boolean) -> Unit = { _, _, _ -> },
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showAddFavoriteDialog by rememberSaveable { mutableStateOf(false) }
    var favoriteToEdit by remember { mutableStateOf<FavoriteSession?>(null) }
    var showTagsSheet by rememberSaveable { mutableStateOf(false) }
    var tagAssignSession by remember { mutableStateOf<String?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    // Export launcher — user picks where to save the file
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val json = pendingExportJson ?: return@rememberLauncherForActivityResult
        pendingExportJson = null
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                snackbarHostState.showSnackbar("Favorites exported")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    // Import launcher — user picks a .json file
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (!json.isNullOrBlank()) {
                    viewModel.importFavoritesJson(json)
                    snackbarHostState.showSnackbar("Favorites imported")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Import failed: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.newAcpSession.collect { event ->
            onNavigateToAcpChat(event.sessionId, event.cwd)
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.exitSelectionMode() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                    }
                    Text("${uiState.selectedSessionIds.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(
                        onClick = { viewModel.deleteSelectedSessions() },
                        enabled = uiState.selectedSessionIds.isNotEmpty(),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete, contentDescription = "Delete",
                            modifier = Modifier.size(20.dp),
                            tint = if (uiState.selectedSessionIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sessions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = onNavigateToHosts, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Servers", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.requestSessions() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                    }
                    if (selectedTabIndex == 2) {
                        IconButton(
                            onClick = { importLauncher.launch("application/json") },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Import", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    pendingExportJson = viewModel.exportFavoritesJson()
                                    exportLauncher.launch("agentshell-favorites.json")
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Export", modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(
                        onClick = {
                            if (selectedTabIndex == 2) showAddFavoriteDialog = true
                            else showCreateDialog = true
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = {
                        if (selectedTabIndex == 2) showAddFavoriteDialog = true
                        else showCreateDialog = true
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New")
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                ) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Terminal", fontSize = 13.sp)
                    }
                }
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                ) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Direct", fontSize = 13.sp)
                    }
                }
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                ) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Favorites", fontSize = 13.sp)
                    }
                }
            }

            val displayedSessions = remember(uiState.tmuxSessions, uiState.activeTagFilter, uiState.sessionTagMap) {
                val filter = uiState.activeTagFilter ?: return@remember uiState.tmuxSessions
                uiState.tmuxSessions.filter { s ->
                    uiState.sessionTagMap[s.name]?.any { it.id == filter } == true
                }
            }

            if (selectedTabIndex == 0 && uiState.tags.isNotEmpty()) {
                TagFilterRow(
                    tags = uiState.tags,
                    activeTagFilter = uiState.activeTagFilter,
                    onTagSelected = { viewModel.setTagFilter(it) },
                    onManageTags = { showTagsSheet = true },
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.requestSessions() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (selectedTabIndex) {
                    0 -> TmuxSessionList(
                        sessions = displayedSessions,
                        isLoading = uiState.isLoading,
                        onTap = { session -> onNavigateToTerminal(session.name) },
                        onChat = { session -> onNavigateToChat(session.name, 0) },
                        onKill = { session -> viewModel.killSession(session.name) },
                        onGit = { session -> onNavigateToGit(session.name, "", false) },
                        sessionTagMap = uiState.sessionTagMap,
                        onManageSessionTags = { sessionName -> tagAssignSession = sessionName },
                    )
                    1 -> AcpSessionList(
                        sessions = uiState.acpSessions,
                        isLoading = uiState.isLoading,
                        isSelectionMode = uiState.isSelectionMode,
                        selectedIds = uiState.selectedSessionIds,
                        onTap = { session ->
                            if (uiState.isSelectionMode) viewModel.toggleSelection(session.sessionId)
                            else onNavigateToAcpChat(session.sessionId, session.cwd)
                        },
                        onLongPress = { session -> viewModel.enterSelectionMode(session.sessionId) },
                        onDelete = { session -> viewModel.deleteAcpSession(session.sessionId) },
                        onGit = { session -> onNavigateToGit(session.sessionId, session.cwd, true) },
                    )
                    2 -> FavoriteSessionsList(
                        favorites = uiState.favorites,
                        tags = uiState.tags,
                        favoriteTagMap = uiState.favoriteTagMap,
                        onLaunch = { favorite ->
                            viewModel.createSessionFromFavorite(favorite)
                            selectedTabIndex = 0
                        },
                        onEdit = { favorite -> favoriteToEdit = favorite },
                        onDelete = { favorite -> viewModel.deleteFavorite(favorite) },
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

    if (showAddFavoriteDialog) {
        FavoriteDialog(
            title = "Add Favorite",
            initialName = "",
            initialPath = "",
            initialStartupCommand = null,
            initialStartupArgs = null,
            availableTags = uiState.tags,
            initialTagIds = emptySet(),
            onConfirm = { name, path, startupCommand, startupArgs, tagIds ->
                viewModel.addFavorite(name, path, startupCommand, startupArgs, tagIds)
                showAddFavoriteDialog = false
            },
            onDismiss = { showAddFavoriteDialog = false },
        )
    }

    favoriteToEdit?.let { fav ->
        FavoriteDialog(
            title = "Edit Favorite",
            initialName = fav.name,
            initialPath = fav.path,
            initialStartupCommand = fav.startupCommand,
            initialStartupArgs = fav.startupArgs,
            availableTags = uiState.tags,
            initialTagIds = (uiState.favoriteTagMap[fav.id] ?: emptyList()).toSet(),
            onConfirm = { name, path, startupCommand, startupArgs, tagIds ->
                viewModel.updateFavorite(fav, name, path, startupCommand, startupArgs, tagIds)
                favoriteToEdit = null
            },
            onDismiss = { favoriteToEdit = null },
        )
    }

    if (showTagsSheet) {
        TagsBottomSheet(
            tags = uiState.tags,
            onAddTag = { name, colorHex -> viewModel.addTag(name, colorHex) },
            onDeleteTag = { tag -> viewModel.deleteTag(tag) },
            onDismiss = { showTagsSheet = false },
        )
    }

    tagAssignSession?.let { sessionName ->
        TagAssignDialog(
            sessionName = sessionName,
            allTags = uiState.tags,
            assignedTagIds = uiState.sessionTagMap[sessionName]?.map { it.id }?.toSet() ?: emptySet(),
            onToggle = { tagId, assigned ->
                if (assigned) viewModel.assignTag(sessionName, tagId)
                else viewModel.removeTagFromSession(sessionName, tagId)
            },
            onManageTags = { tagAssignSession = null; showTagsSheet = true },
            onDismiss = { tagAssignSession = null },
        )
    }
}

// ── Favorites ─────────────────────────────────────────────────────────────────

@Composable
private fun FavoriteSessionsList(
    favorites: List<FavoriteSession>,
    tags: List<SessionTag> = emptyList(),
    favoriteTagMap: Map<String, List<String>> = emptyMap(),
    onLaunch: (FavoriteSession) -> Unit,
    onEdit: (FavoriteSession) -> Unit,
    onDelete: (FavoriteSession) -> Unit,
) {
    if (favorites.isEmpty()) {
        EmptySessionsMessage(
            icon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(64.dp)) },
            message = "No favorites yet",
            hint = "Tap + to add a favorite tmux session",
        )
        return
    }

    val tagById = remember(tags) { tags.associateBy { it.id } }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(favorites, key = { it.id }) { favorite ->
            val assignedTags = favoriteTagMap[favorite.id]
                ?.mapNotNull { tagById[it] }
                ?: emptyList()
            FavoriteSessionCard(
                favorite = favorite,
                assignedTags = assignedTags,
                onLaunch = { onLaunch(favorite) },
                onEdit = { onEdit(favorite) },
                onDelete = { onDelete(favorite) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteSessionCard(
    favorite: FavoriteSession,
    assignedTags: List<SessionTag> = emptyList(),
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .combinedClickable(onClick = onLaunch, onLongClick = { showMenu = true }),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(favorite.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    favorite.path,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
                if (!favorite.startupCommand.isNullOrBlank()) {
                    val cmdLabel = buildString {
                        append("$ ")
                        append(favorite.startupCommand)
                        if (!favorite.startupArgs.isNullOrBlank()) append(" ${favorite.startupArgs}")
                    }
                    Text(
                        cmdLabel,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
                if (assignedTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        assignedTags.forEach { tag ->
                            val tagColor = runCatching {
                                android.graphics.Color.parseColor(tag.colorHex)
                            }.getOrDefault(0xFF2196F3.toInt())
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Color(tagColor),
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onLaunch,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Launch", fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { showMenu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; showDeleteDialog = true },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Favorite") },
            text = { Text("Remove \"${favorite.name}\" from favorites?") },
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
private fun FavoriteDialog(
    title: String,
    initialName: String,
    initialPath: String,
    initialStartupCommand: String? = null,
    initialStartupArgs: String? = null,
    availableTags: List<SessionTag> = emptyList(),
    initialTagIds: Set<String> = emptySet(),
    onConfirm: (name: String, path: String, startupCommand: String?, startupArgs: String?, tagIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var path by rememberSaveable { mutableStateOf(initialPath) }
    var selectedTagIds by remember { mutableStateOf(initialTagIds) }

    val presets = listOf("None", "claude", "codex", "opencode", "Custom")
    var selectedPreset by rememberSaveable {
        mutableStateOf(
            when {
                initialStartupCommand.isNullOrBlank() -> "None"
                initialStartupCommand in listOf("claude", "codex", "opencode") -> initialStartupCommand
                else -> "Custom"
            }
        )
    }
    var customCommand by rememberSaveable {
        mutableStateOf(
            if (!initialStartupCommand.isNullOrBlank() &&
                initialStartupCommand !in listOf("claude", "codex", "opencode")
            ) initialStartupCommand else ""
        )
    }
    var startupArgs by rememberSaveable { mutableStateOf(initialStartupArgs ?: "") }

    val effectiveCommand: String? = when (selectedPreset) {
        "None" -> null
        "Custom" -> customCommand.trim().takeIf { it.isNotBlank() }
        else -> selectedPreset
    }
    val effectiveArgs: String? = startupArgs.trim().takeIf { it.isNotBlank() }

    val valid = name.isNotBlank() && path.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session Name") },
                    placeholder = { Text("e.g., my-project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Absolute Path") },
                    placeholder = { Text("e.g., /home/user/projects/myapp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Startup Command (optional)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset, fontSize = 12.sp) },
                        )
                    }
                }
                if (selectedPreset == "Custom") {
                    OutlinedTextField(
                        value = customCommand,
                        onValueChange = { customCommand = it },
                        label = { Text("Command") },
                        placeholder = { Text("e.g., nvim") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (selectedPreset != "None") {
                    OutlinedTextField(
                        value = startupArgs,
                        onValueChange = { startupArgs = it },
                        label = { Text("Arguments (optional)") },
                        placeholder = { Text("e.g., --resume") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (availableTags.isNotEmpty()) {
                    Text(
                        "Tags (optional)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableTags.forEach { tag ->
                            val selected = tag.id in selectedTagIds
                            val tagColor = runCatching {
                                android.graphics.Color.parseColor(tag.colorHex)
                            }.getOrDefault(0xFF2196F3.toInt())
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedTagIds = if (selected)
                                        selectedTagIds - tag.id
                                    else
                                        selectedTagIds + tag.id
                                },
                                label = { Text(tag.name, fontSize = 12.sp) },
                                leadingIcon = if (selected) null else {
                                    {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    androidx.compose.ui.graphics.Color(tagColor),
                                                    androidx.compose.foundation.shape.CircleShape,
                                                )
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(name.trim(), path.trim(), effectiveCommand, effectiveArgs, selectedTagIds.toList()) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Existing composables ───────────────────────────────────────────────────────

@Composable
private fun TmuxSessionList(
    sessions: List<TmuxSession>,
    isLoading: Boolean,
    onTap: (TmuxSession) -> Unit,
    onChat: (TmuxSession) -> Unit,
    onKill: (TmuxSession) -> Unit,
    onGit: (TmuxSession) -> Unit,
    sessionTagMap: Map<String, List<SessionTag>> = emptyMap(),
    onManageSessionTags: (sessionName: String) -> Unit = {},
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
                tags = sessionTagMap[session.name] ?: emptyList(),
                onTap = { onTap(session) },
                onChat = { onChat(session) },
                onKill = { onKill(session) },
                onGit = { onGit(session) },
                onManageTags = { onManageSessionTags(session.name) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TmuxSessionCard(
    session: TmuxSession,
    tags: List<SessionTag> = emptyList(),
    onTap: () -> Unit,
    onChat: () -> Unit,
    onKill: () -> Unit,
    onGit: () -> Unit,
    onManageTags: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var showKillDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .combinedClickable(onClick = onTap, onLongClick = { showMenu = true }),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (session.attached) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "${session.windows} win${if (session.attached) " • Active" else ""}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        tags.forEach { tag ->
                            val tagColor = try {
                                Color(android.graphics.Color.parseColor(tag.colorHex))
                            } catch (e: Exception) { Color(0xFF2196F3) }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(tagColor, shape = androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                }
            }

            // AI tool indicator dot
            session.tool?.let { tool ->
                val dotColor = when (tool) {
                    "claude" -> Color(0xFFFF8C00)
                    "codex" -> Color(0xFF2196F3)
                    "opencode" -> Color(0xFF4CAF50)
                    "kiro" -> Color(0xFF9C27B0)
                    else -> Color(0xFF9E9E9E)
                }
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(dotColor, shape = androidx.compose.foundation.shape.CircleShape),
                )
            }

            FilledTonalButton(
                onClick = onTap,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Term", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onChat,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Outlined.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Chat", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onGit,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Git", fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Git") },
                        leadingIcon = { Icon(Icons.Default.Code, null) },
                        onClick = { showMenu = false; onGit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Tags") },
                        leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                        onClick = { showMenu = false; onManageTags() },
                    )
                    DropdownMenuItem(
                        text = { Text("Kill Session", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; showKillDialog = true },
                    )
                }
            }
        }
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
    onGit: (AcpSession) -> Unit,
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
                onGit = { onGit(session) },
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
    onGit: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val displayTitle = session.title.ifBlank { session.cwd.substringAfterLast('/') }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onGit,
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Git", fontSize = 12.sp)
                        }
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

// ── Tag filter row ─────────────────────────────────────────────────────────────

@Composable
private fun TagFilterRow(
    tags: List<SessionTag>,
    activeTagFilter: String?,
    onTagSelected: (String?) -> Unit,
    onManageTags: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = activeTagFilter == null,
            onClick = { onTagSelected(null) },
            label = { Text("All", fontSize = 12.sp) },
        )
        tags.forEach { tag ->
            val tagColor = try {
                Color(android.graphics.Color.parseColor(tag.colorHex))
            } catch (e: Exception) { Color(0xFF2196F3) }
            FilterChip(
                selected = activeTagFilter == tag.id,
                onClick = { onTagSelected(if (activeTagFilter == tag.id) null else tag.id) },
                label = { Text(tag.name, fontSize = 12.sp) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(tagColor, shape = androidx.compose.foundation.shape.CircleShape),
                    )
                },
            )
        }
        TextButton(onClick = onManageTags, modifier = Modifier.height(32.dp)) {
            Text("Edit", fontSize = 12.sp)
        }
    }
}

// ── Tags management bottom sheet ───────────────────────────────────────────────

private val TAG_COLOR_PALETTE = listOf(
    "#2196F3", "#4CAF50", "#F44336", "#FF9800",
    "#9C27B0", "#00BCD4", "#FF5722", "#607D8B",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TagsBottomSheet(
    tags: List<SessionTag>,
    onAddTag: (name: String, colorHex: String) -> Unit,
    onDeleteTag: (SessionTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(TAG_COLOR_PALETTE[0]) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Session Tags",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Color palette
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TAG_COLOR_PALETTE.forEach { hex ->
                    val color = try {
                        Color(android.graphics.Color.parseColor(hex))
                    } catch (e: Exception) { Color.Gray }
                    Box(
                        modifier = Modifier
                            .size(if (selectedColor == hex) 28.dp else 22.dp)
                            .background(color, shape = androidx.compose.foundation.shape.CircleShape)
                            .combinedClickable(onClick = { selectedColor = hex }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selectedColor == hex) {
                            Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // New tag name + add button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Tag name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onAddTag(newName.trim(), selectedColor)
                            newName = ""
                        }
                    },
                ) { Text("Add") }
            }

            // Existing tags
            if (tags.isNotEmpty()) {
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(tags, key = { it.id }) { tag ->
                        val tagColor = try {
                            Color(android.graphics.Color.parseColor(tag.colorHex))
                        } catch (e: Exception) { Color.Gray }
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(tagColor, shape = androidx.compose.foundation.shape.CircleShape),
                                )
                            },
                            headlineContent = { Text(tag.name) },
                            trailingContent = {
                                IconButton(onClick = { onDeleteTag(tag) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete tag")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Tag assign dialog ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagAssignDialog(
    sessionName: String,
    allTags: List<SessionTag>,
    assignedTagIds: Set<String>,
    onToggle: (tagId: String, assign: Boolean) -> Unit,
    onManageTags: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags for \"$sessionName\"") },
        text = {
            if (allTags.isEmpty()) {
                Text("No tags yet. Create tags first.", color = MaterialTheme.colorScheme.outline)
            } else {
                Column {
                    allTags.forEach { tag ->
                        val tagColor = try {
                            Color(android.graphics.Color.parseColor(tag.colorHex))
                        } catch (e: Exception) { Color.Gray }
                        val assigned = tag.id in assignedTagIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onToggle(tag.id, !assigned) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(tagColor, shape = androidx.compose.foundation.shape.CircleShape),
                            )
                            Text(tag.name, modifier = Modifier.weight(1f))
                            Switch(
                                checked = assigned,
                                onCheckedChange = { onToggle(tag.id, it) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onManageTags) { Text("Manage Tags") }
        },
    )
}
