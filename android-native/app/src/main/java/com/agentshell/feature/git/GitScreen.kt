package com.agentshell.feature.git

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.data.model.GitBranchInfo
import com.agentshell.data.model.GitCommitInfo
import com.agentshell.data.model.GitFileChange
import com.agentshell.data.model.GitGraphNode

// Color constants
private val AdditionColor = Color(0xFF22C55E)
private val DeletionColor = Color(0xFFEF4444)
private val ModifiedColor = Color(0xFFF59E0B)
private val UntrackedColor = Color(0xFF8B5CF6)
private val ConflictColor = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    sessionName: String?,
    initialPath: String?,
    isAcp: Boolean = false,
    onNavigateBack: () -> Unit,
    viewModel: GitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sessionName, initialPath, isAcp) {
        viewModel.initialize(sessionName, initialPath, isAcp)
    }

    // Show operation results as snackbar
    LaunchedEffect(state.operationResult) {
        state.operationResult?.let { result ->
            val msg = if (result.success) {
                "${result.operation}: ${result.message ?: "Success"}"
            } else {
                "${result.operation} failed: ${result.error ?: "Unknown error"}"
            }
            snackbarHostState.showSnackbar(msg.take(120))
            viewModel.clearOperationResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.repoName.ifEmpty { "Git" },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.status.branch.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Code, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    state.status.branch,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (state.status.ahead > 0 || state.status.behind > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        buildString {
                                            if (state.status.ahead > 0) append("\u2191${state.status.ahead}")
                                            if (state.status.behind > 0) append(" \u2193${state.status.behind}")
                                        }.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.pull() }) {
                        Icon(Icons.Default.Download, contentDescription = "Pull", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.push() }) {
                        Icon(Icons.Default.Upload, contentDescription = "Push", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Tabs
            TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                GitTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                when (tab) {
                                    GitTab.STATUS -> "Status"
                                    GitTab.BRANCHES -> "Branches"
                                    GitTab.GRAPH -> "Graph"
                                },
                                fontSize = 13.sp,
                            )
                            if (tab == GitTab.STATUS && state.status.totalChanges > 0) {
                                Badge { Text("${state.status.totalChanges}") }
                            }
                        }
                    }
                }
            }

            // Content
            when (state.selectedTab) {
                GitTab.STATUS -> StatusTab(state, viewModel)
                GitTab.BRANCHES -> BranchesTab(state, viewModel)
                GitTab.GRAPH -> GraphTab(state, viewModel)
            }
        }
    }

    // Bottom sheets
    if (state.showDiffSheet) {
        DiffBottomSheet(state, viewModel)
    }
    if (state.showCommitSheet) {
        CommitBottomSheet(state, viewModel)
    }
    if (state.showCommitDetailSheet && state.selectedCommit != null) {
        CommitDetailBottomSheet(state.selectedCommit!!, state, viewModel)
    }
}

// =============================================================================
// STATUS TAB
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusTab(state: GitUiState, viewModel: GitViewModel) {
    PullToRefreshBox(
        isRefreshing = state.isLoadingStatus,
        onRefresh = { viewModel.refreshStatus() },
        modifier = Modifier.fillMaxSize(),
    ) {
        // Show error if present
        if (state.error != null && !state.isLoadingStatus) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(48.dp), tint = DeletionColor)
                    Spacer(Modifier.height(8.dp))
                    Text("Error", style = MaterialTheme.typography.titleMedium, color = DeletionColor)
                    Spacer(Modifier.height(4.dp))
                    Text(state.error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.clearError(); viewModel.refreshStatus() }) {
                        Text("Retry")
                    }
                }
            }
            return@PullToRefreshBox
        }

        if (!state.isLoadingStatus && !state.status.hasChanges) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(48.dp), tint = AdditionColor)
                    Spacer(Modifier.height(8.dp))
                    Text("Working tree clean", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                }
            }
            return@PullToRefreshBox
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            // Staged files
            if (state.status.staged.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Staged (${state.status.staged.size})",
                        color = AdditionColor,
                        action = { TextButton(onClick = { viewModel.unstageAll() }) { Text("Unstage All", fontSize = 11.sp) } },
                    )
                }
                items(state.status.staged, key = { "staged-${it.path}" }) { file ->
                    FileChangeRow(
                        file = file,
                        statusColor = AdditionColor,
                        onTap = { viewModel.viewDiff(file.path, staged = true) },
                        trailingAction = {
                            TextButton(onClick = { viewModel.unstageFile(file.path) }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Unstage", fontSize = 10.sp)
                            }
                        },
                    )
                }
            }

            // Modified files
            if (state.status.modified.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Modified (${state.status.modified.size})",
                        color = ModifiedColor,
                        action = { TextButton(onClick = { viewModel.stageAll() }) { Text("Stage All", fontSize = 11.sp) } },
                    )
                }
                items(state.status.modified, key = { "mod-${it.path}" }) { file ->
                    FileChangeRow(
                        file = file,
                        statusColor = ModifiedColor,
                        onTap = { viewModel.viewDiff(file.path, staged = false) },
                        trailingAction = {
                            TextButton(onClick = { viewModel.stageFile(file.path) }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Stage", fontSize = 10.sp)
                            }
                        },
                    )
                }
            }

            // Untracked files
            if (state.status.untracked.isNotEmpty()) {
                item {
                    SectionHeader(title = "Untracked (${state.status.untracked.size})", color = UntrackedColor)
                }
                items(state.status.untracked, key = { "untracked-$it" }) { path ->
                    UntrackedFileRow(
                        path = path,
                        onStage = { viewModel.stageFile(path) },
                    )
                }
            }

            // Conflicts
            if (state.status.conflicts.isNotEmpty()) {
                item {
                    SectionHeader(title = "Conflicts (${state.status.conflicts.size})", color = ConflictColor)
                }
                items(state.status.conflicts, key = { "conflict-$it" }) { path ->
                    ConflictFileRow(path)
                }
            }

            // Commit button
            if (state.status.hasChanges) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.showCommitForm() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Commit")
                        }
                        OutlinedButton(
                            onClick = { viewModel.stashPush() },
                        ) {
                            Text("Stash")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    color: Color,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(8.dp).background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileChangeRow(
    file: GitFileChange,
    statusColor: Color,
    onTap: () -> Unit,
    trailingAction: @Composable () -> Unit,
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).combinedClickable(
            onClick = onTap,
            onLongClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(file.path))
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                file.status,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.filename, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (file.directory.isNotEmpty()) {
                    Text(file.directory, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (file.additions != null || file.deletions != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    buildAnnotatedString {
                        file.additions?.let {
                            withStyle(SpanStyle(color = AdditionColor)) { append("+$it") }
                        }
                        if (file.additions != null && file.deletions != null) append(" ")
                        file.deletions?.let {
                            withStyle(SpanStyle(color = DeletionColor)) { append("-$it") }
                        }
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(4.dp))
            trailingAction()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UntrackedFileRow(path: String, onStage: () -> Unit) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).combinedClickable(
            onClick = {},
            onLongClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(path))
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("?", color = UntrackedColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(path.substringAfterLast('/'), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onStage, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Stage", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ConflictFileRow(path: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = ConflictColor.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("!", color = ConflictColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(path.substringAfterLast('/'), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("CONFLICT", fontSize = 10.sp, color = ConflictColor, fontWeight = FontWeight.Bold)
        }
    }
}

// =============================================================================
// BRANCHES TAB
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchesTab(state: GitUiState, viewModel: GitViewModel) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    PullToRefreshBox(
        isRefreshing = state.isLoadingBranches,
        onRefresh = { viewModel.refreshBranches() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        ) {
            items(state.branches, key = { it.name }) { branch ->
                BranchCard(
                    branch = branch,
                    onCheckout = { viewModel.checkoutBranch(branch.name) },
                    onDelete = { showDeleteDialog = branch.name },
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Branch")
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateBranchDialog(
            onConfirm = { name -> viewModel.createBranch(name); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }

    showDeleteDialog?.let { branchName ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Branch") },
            text = { Text("Delete branch \"$branchName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteBranch(branchName); showDeleteDialog = null }) {
                    Text("Delete", color = DeletionColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BranchCard(
    branch: GitBranchInfo,
    onCheckout: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        colors = if (branch.current) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (branch.current) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(branch.name, fontWeight = if (branch.current) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    branch.tracking?.let {
                        Text("\u2192 $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    if ((branch.ahead ?: 0) > 0 || (branch.behind ?: 0) > 0) {
                        Text(
                            buildString {
                                branch.ahead?.takeIf { it > 0 }?.let { append("\u2191$it") }
                                branch.behind?.takeIf { it > 0 }?.let { if (isNotEmpty()) append(" "); append("\u2193$it") }
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    branch.lastCommit?.let {
                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            if (!branch.current) {
                OutlinedButton(
                    onClick = onCheckout,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("Switch", fontSize = 11.sp)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, null, tint = DeletionColor, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CreateBranchDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Branch") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace(" ", "-") },
                label = { Text("Branch name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================
// GRAPH TAB
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphTab(state: GitUiState, viewModel: GitViewModel) {
    val listState = rememberLazyListState()

    // Load initial data
    LaunchedEffect(Unit) {
        if (state.commits.isEmpty()) viewModel.refreshLog()
    }

    // Infinite scroll
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIdx ->
                if (lastIdx != null && lastIdx >= state.graphNodes.size - 5 && state.hasMoreCommits && !state.isLoadingLog) {
                    viewModel.loadMoreCommits()
                }
            }
    }

    PullToRefreshBox(
        isRefreshing = state.isLoadingLog,
        onRefresh = { viewModel.refreshLog() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.graphNodes.isEmpty() && !state.isLoadingLog) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No commits", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
            return@PullToRefreshBox
        }

        // Compute max columns across all nodes for consistent width
        val maxCol = (state.graphNodes.maxOfOrNull { node ->
            maxOf(node.column, node.lanes.maxOfOrNull { it.column } ?: 0, node.connections.maxOfOrNull { maxOf(it.fromColumn, it.toColumn) } ?: 0)
        } ?: 0) + 1
        val colWidth = 20.dp
        val graphWidth = colWidth * maxCol + 12.dp

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(state.graphNodes, key = { _, node -> node.hash }) { index, node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.showCommitDetail(GitCommitInfo(node.hash, node.shortHash, node.message, node.author, node.date, node.parents, node.refs)) }
                        .padding(vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Graph lanes
                    val surfaceColor = MaterialTheme.colorScheme.surface
                    Box(modifier = Modifier.width(graphWidth).height(36.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cw = colWidth.toPx()
                            val pad = 6.dp.toPx()
                            val centerY = size.height / 2
                            val strokeW = 2.dp.toPx()
                            fun colX(c: Int) = c * cw + cw / 2 + pad

                            // 1. Draw vertical pass-through lines for all active lanes
                            for (lane in node.lanes) {
                                val lx = colX(lane.column)
                                val laneColor = Color(lane.color)
                                if (lane.column != node.column) {
                                    // Full vertical line (this lane just passes through)
                                    drawLine(laneColor, Offset(lx, 0f), Offset(lx, size.height), strokeW)
                                } else {
                                    // This lane holds the commit node — draw top half only
                                    drawLine(laneColor, Offset(lx, 0f), Offset(lx, centerY), strokeW)
                                }
                            }

                            // 2. Draw connections from this commit to the next row
                            for (conn in node.connections) {
                                val fromX = colX(conn.fromColumn)
                                val toX = colX(conn.toColumn)
                                val connColor = Color(conn.color)

                                if (conn.fromColumn == conn.toColumn) {
                                    // Straight down from center
                                    drawLine(connColor, Offset(fromX, centerY), Offset(fromX, size.height), strokeW)
                                } else {
                                    // Diagonal: from commit center to target column at bottom
                                    // Draw as two segments: vertical down then diagonal
                                    val midY = centerY + (size.height - centerY) * 0.3f
                                    drawLine(connColor, Offset(fromX, centerY), Offset(fromX, midY), strokeW)
                                    drawLine(connColor, Offset(fromX, midY), Offset(toX, size.height), strokeW)
                                }
                            }

                            // 3. Draw commit node dot (on top of lines)
                            val nodeX = colX(node.column)
                            val nodeColor = Color(node.color)
                            val dotRadius = 4.5.dp.toPx()
                            val innerRadius = 2.5.dp.toPx()

                            drawCircle(nodeColor, dotRadius, Offset(nodeX, centerY))
                            drawCircle(surfaceColor, innerRadius, Offset(nodeX, centerY))
                            drawCircle(nodeColor, innerRadius, Offset(nodeX, centerY), style = Stroke(1.5.dp.toPx()))
                        }
                    }

                    // Commit info
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        // Ref badges
                        if (node.refs.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                node.refs.forEach { ref ->
                                    val isHead = ref.contains("HEAD")
                                    val isTag = ref.startsWith("tag: ")
                                    val bgColor = when {
                                        isHead -> MaterialTheme.colorScheme.primary
                                        isTag -> ModifiedColor
                                        else -> Color(node.color)
                                    }
                                    Text(
                                        ref.removePrefix("tag: "),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        modifier = Modifier
                                            .background(bgColor, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(node.message, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(node.shortHash, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                            Text(node.author, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                            Text(formatRelativeDate(node.date), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            if (state.isLoadingLog) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

// =============================================================================
// DIFF BOTTOM SHEET
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffBottomSheet(state: GitUiState, viewModel: GitViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeDiffSheet() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            if (state.isLoadingDiff) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                state.currentDiff?.let { diff ->
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(diff.filePath.substringAfterLast('/'), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(diff.filePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = AdditionColor)) { append("+${diff.additions}") }
                                append(" ")
                                withStyle(SpanStyle(color = DeletionColor)) { append("-${diff.deletions}") }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // Diff content
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                        ) {
                            val lines = diff.diff.lines()
                            items(lines.size) { idx ->
                                val line = lines[idx]
                                val bgColor = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> AdditionColor.copy(alpha = 0.15f)
                                    line.startsWith("-") && !line.startsWith("---") -> DeletionColor.copy(alpha = 0.15f)
                                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else -> Color.Transparent
                                }
                                val textColor = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> AdditionColor
                                    line.startsWith("-") && !line.startsWith("---") -> DeletionColor
                                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }

                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 1.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                } ?: Text("No diff data", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// =============================================================================
// COMMIT BOTTOM SHEET
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitBottomSheet(state: GitUiState, viewModel: GitViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { viewModel.hideCommitForm() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text("Commit Changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // Commit message
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = { viewModel.updateCommitMessage(it) },
                label = { Text("Commit message") },
                placeholder = { Text("Describe your changes...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )

            Spacer(Modifier.height(12.dp))

            // File selection
            Text("Files to commit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))

            val allFiles = state.status.staged.map { it.path } +
                state.status.modified.map { it.path } +
                state.status.untracked

            LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                items(allFiles) { filePath ->
                    val isSelected = state.selectedFiles.contains(filePath)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleFileSelection(filePath) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { viewModel.toggleFileSelection(filePath) },
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(filePath, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.hideCommitForm() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                FilledTonalButton(
                    onClick = { viewModel.commit() },
                    enabled = state.commitMessage.isNotBlank() && state.selectedFiles.isNotEmpty() && !state.isCommitting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isCommitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Commit (${state.selectedFiles.size})")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Push after commit option
            OutlinedButton(
                onClick = {
                    viewModel.commit()
                    viewModel.push()
                },
                enabled = state.commitMessage.isNotBlank() && state.selectedFiles.isNotEmpty() && !state.isCommitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Commit & Push")
            }
        }
    }
}

// =============================================================================
// COMMIT DETAIL BOTTOM SHEET
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CommitDetailBottomSheet(commit: GitCommitInfo, state: GitUiState, viewModel: GitViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = { viewModel.hideCommitDetail() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            // Hash
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(commit.shortHash, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                if (commit.isMerge) {
                    Text(
                        "MERGE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.background(UntrackedColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Ref badges
            if (commit.refs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    commit.refs.forEach { ref ->
                        Text(
                            ref,
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Message
            Text(commit.message, fontWeight = FontWeight.Medium, fontSize = 15.sp)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Details
            DetailRow("Author", commit.author)
            DetailRow("Date", commit.date)
            DetailRow("Full Hash", commit.hash)
            if (commit.parents.isNotEmpty()) {
                DetailRow("Parents", commit.parents.joinToString(", ") { it.take(7) })
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Changed files section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Changed Files",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.commitFiles.isNotEmpty()) {
                    Text("${state.commitFiles.size} files", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(4.dp))

            if (state.isLoadingCommitFiles) {
                Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (state.commitFiles.isEmpty()) {
                Text("No files changed", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                    items(state.commitFiles, key = { it.path }) { file ->
                        val statusColor = when (file.status) {
                            "A" -> AdditionColor
                            "D" -> DeletionColor
                            "M" -> ModifiedColor
                            "R" -> UntrackedColor
                            else -> ModifiedColor
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .combinedClickable(
                                    onClick = { viewModel.viewCommitFileDiff(commit.hash, file.path) },
                                    onLongClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(file.path))
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    },
                                ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    file.status,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.filename, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (file.directory.isNotEmpty()) {
                                        Text(file.directory, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (file.additions != null || file.deletions != null) {
                                    Text(
                                        buildAnnotatedString {
                                            file.additions?.let { withStyle(SpanStyle(color = AdditionColor)) { append("+$it") } }
                                            if (file.additions != null && file.deletions != null) append(" ")
                                            file.deletions?.let { withStyle(SpanStyle(color = DeletionColor)) { append("-$it") } }
                                        },
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
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

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(80.dp))
        SelectionContainer {
            Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// =============================================================================
// HELPERS
// =============================================================================

private fun formatRelativeDate(isoDate: String): String {
    // Simple relative date formatting from ISO date string
    return try {
        val instant = java.time.Instant.parse(isoDate.replace("+00:00", "Z").let { if (it.endsWith("Z")) it else "${it}Z" })
        val now = java.time.Instant.now()
        val duration = java.time.Duration.between(instant, now)
        when {
            duration.toMinutes() < 1 -> "just now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
            duration.toHours() < 24 -> "${duration.toHours()}h ago"
            duration.toDays() < 30 -> "${duration.toDays()}d ago"
            duration.toDays() < 365 -> "${duration.toDays() / 30}mo ago"
            else -> "${duration.toDays() / 365}y ago"
        }
    } catch (_: Exception) {
        isoDate.take(10)
    }
}
