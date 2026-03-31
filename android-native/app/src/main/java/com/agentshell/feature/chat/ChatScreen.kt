package com.agentshell.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dvr
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.ClaudeUsage
import com.agentshell.feature.common.SwipeableSessionContainer
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionName: String,
    windowIndex: Int = 0,
    isAcp: Boolean = false,
    cwd: String = "",
    onNavigateBack: () -> Unit = {},
    onSwitchToTerminal: ((sessionName: String) -> Unit)? = null,
    onSwipeToChatSession: ((sessionName: String, windowIndex: Int, isAcp: Boolean) -> Unit)? = null,
    isSwipeNavigation: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val claudeUsage by viewModel.claudeUsage.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showScrollButton by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showVoiceButton by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Swipe navigation state
    val chatKey = if (isAcp) "acp:$sessionName" else "tmux:$sessionName\u0000$windowIndex"
    var recentChatSessions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(chatKey) {
        if (!isSwipeNavigation) {
            viewModel.pushRecentChatSession(chatKey)
        }
        recentChatSessions = viewModel.getRecentChatSessions()
    }

    // Load voice button preference
    LaunchedEffect(Unit) {
        showVoiceButton = viewModel.isVoiceButtonVisible()
    }

    // Show snackbar on chat cleared
    LaunchedEffect(Unit) {
        viewModel.chatCleared.collect { success ->
            snackbarHostState.showSnackbar(
                if (success) "Chat history cleared" else "Failed to clear chat"
            )
        }
    }

    // Mic permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: "application/octet-stream"
        var filename = "file"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) filename = cursor.getString(nameIdx) ?: "file"
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        viewModel.attachFile(uri.toString(), filename, mimeType, size)
    }

    // Auto-insert transcribed voice text into draft
    LaunchedEffect(uiState.transcribedText) {
        val text = uiState.transcribedText ?: return@LaunchedEffect
        if (text.isNotBlank()) {
            val current = uiState.draftMessage
            val newDraft = if (current.isBlank()) text else "$current $text"
            viewModel.updateDraft(newDraft)
            viewModel.clearTranscribedText()
            if (viewModel.isVoiceAutoEnter()) {
                viewModel.sendMessage(newDraft)
            }
        }
    }

    // Initialise the chat watch on first composition
    LaunchedEffect(sessionName, windowIndex, isAcp) {
        if (isAcp) {
            viewModel.startAcpChat(sessionName, cwd)
        } else {
            viewModel.watchChatLog(sessionName, windowIndex)
        }
    }

    // Auto-scroll when new messages arrive and user is near the bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect {
                if (autoScroll && it > 0) {
                    coroutineScope.launch {
                        listState.animateScrollToItem(it - 1)
                    }
                }
            }
    }

    // Load more when near the top
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .filter { it < 3 }
            .collect {
                if (uiState.hasMoreMessages && !uiState.isLoadingMore && !uiState.isLoading) {
                    viewModel.loadMore()
                }
            }
    }

    // Track scroll position for the "scroll to bottom" FAB
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible < (info.totalItemsCount - 2)
        }.distinctUntilChanged().collect { notAtBottom ->
            showScrollButton = notAtBottom
            if (!notAtBottom) autoScroll = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isAcp) "AI Chat" else sessionName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!isAcp && windowIndex > 0) {
                            Text(
                                text = "Window $windowIndex",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onSwitchToTerminal != null) {
                        IconButton(onClick = { onSwitchToTerminal(sessionName) }) {
                            Icon(Icons.Filled.Dvr, contentDescription = "Switch to Terminal")
                        }
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            onClick = {
                                showMenu = false
                                showClearConfirm = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding(),
    ) { innerPadding ->
        SwipeableSessionContainer(
            recentSessions = recentChatSessions,
            currentSessionKey = chatKey,
            sessionLabel = { key ->
                if (key.startsWith("acp:")) key.removePrefix("acp:")
                else key.removePrefix("tmux:").substringBefore("\u0000")
            },
            onSwipeToSession = { key ->
                if (key.startsWith("acp:")) {
                    onSwipeToChatSession?.invoke(key.removePrefix("acp:"), 0, true)
                } else {
                    val parts = key.removePrefix("tmux:").split("\u0000")
                    onSwipeToChatSession?.invoke(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0, false)
                }
            },
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Claude usage banner (only when session runs Claude)
                if (uiState.detectedTool == "claude") {
                    val usage = claudeUsage?.takeIf { it.error == null }
                    val hasApiUsage = usage?.fiveHour != null || usage?.sevenDay != null
                    val hasCtxUsage = uiState.contextWindowUsage != null
                    if (hasApiUsage || hasCtxUsage) {
                        ChatClaudeUsageBanner(usage, uiState.contextWindowUsage, uiState.modelName)
                    }
                }

                // Loading indicator at top for load-more
                if (uiState.isLoadingMore) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

                // Message list
                if (uiState.isLoading) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = uiState.messages,
                            key = { it.id },
                        ) { message ->
                            MessageBubble(
                                message = message,
                                showThinking = uiState.showThinking,
                                showToolCalls = uiState.showToolCalls,
                                fileBaseUrl = uiState.fileBaseUrl,
                                audioPlayerManager = viewModel.audioPlayerManager,
                            )
                        }

                        if (uiState.isStreaming) {
                            item(key = "streaming_indicator") {
                                StreamingIndicator()
                            }
                        }
                    }
                }

                // Error banner
                if (uiState.error != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Bottom input bar
                ChatInputBar(
                    value = uiState.draftMessage,
                    onValueChange = viewModel::updateDraft,
                    onSend = { viewModel.sendMessage(uiState.draftMessage) },
                    isStreaming = uiState.isStreaming,
                    showVoiceButton = showVoiceButton,
                    onCancelRecording = viewModel::cancelVoiceRecording,
                    isRecording = uiState.isRecording,
                    isTranscribing = uiState.isTranscribing,
                    recordingDuration = uiState.recordingDuration,
                    onMicPressed = {
                        if (uiState.isRecording) {
                            viewModel.stopVoiceRecording()
                        } else {
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startVoiceRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    attachedFile = uiState.attachedFile,
                    isUploading = uiState.isUploading,
                    onAttachClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onRemoveAttachment = viewModel::removeAttachedFile,
                )
            }

            // Scroll to bottom FAB
            AnimatedVisibility(
                visible = showScrollButton,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) listState.animateScrollToItem(count - 1)
                        }
                        autoScroll = true
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                }
            }

            // Permission card overlay
            val permission = uiState.pendingPermission
            if (permission != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AcpPermissionCard(
                        permission = permission,
                        onRespond = { requestId, optionId ->
                            viewModel.respondPermission(requestId, optionId)
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

        }
        } // SwipeableSessionContainer
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Chat") },
            text = { Text("Are you sure you want to clear the chat history? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearChat()
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Chat input bar
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isStreaming: Boolean,
    showVoiceButton: Boolean = false,
    onCancelRecording: () -> Unit = {},
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    recordingDuration: Int = 0,
    onMicPressed: () -> Unit = {},
    attachedFile: AttachedFile? = null,
    isUploading: Boolean = false,
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // File preview strip
            AnimatedVisibility(visible = attachedFile != null) {
                attachedFile?.let { file ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatFileSize(file.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = onRemoveAttachment,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Recording indicator strip
            AnimatedVisibility(visible = isRecording || isTranscribing) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isRecording) Color(0x20EF4444) else Color(0x206366F1),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isTranscribing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF6366F1),
                            )
                            Text(
                                text = "Transcribing…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF6366F1),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFEF4444), CircleShape),
                            )
                            Text(
                                text = "Recording %d:%02d".format(
                                    recordingDuration / 60, recordingDuration % 60,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFEF4444),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onCancelRecording,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel recording",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onAttachClick) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(24.dp),
                )

                // Mic button
                if (showVoiceButton) {
                    IconButton(
                        onClick = onMicPressed,
                        enabled = !isTranscribing,
                    ) {
                        when {
                            isTranscribing -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            isRecording -> Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop recording",
                                tint = Color(0xFFEF4444),
                            )
                            else -> Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice input",
                                tint = Color(0xFF6366F1),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = onSend,
                    enabled = (value.isNotBlank() || attachedFile != null) && !isStreaming && !isUploading,
                ) {
                    if (isStreaming || isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (value.isNotBlank() || attachedFile != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

// ---------------------------------------------------------------------------
// Streaming dots indicator
// ---------------------------------------------------------------------------

@Composable
private fun StreamingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 500,
                    delayMillis = index * 150,
                ),
                label = "dotAlpha$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

// ── Claude Usage Banner (chat-specific, compact) ─────────────────────────────

private fun chatUsageColor(pct: Double): Color = when {
    pct >= 80 -> Color(0xFFEF4444)
    pct >= 50 -> Color(0xFFF59E0B)
    else -> Color(0xFF10B981)
}

private fun chatResetCountdown(resetsAt: String): String {
    return try {
        val reset = Instant.parse(resetsAt)
        val mins = ChronoUnit.MINUTES.between(Instant.now(), reset)
        if (mins <= 0) "now"
        else if (mins < 60) "${mins}m"
        else "${mins / 60}h ${mins % 60}m"
    } catch (_: Exception) { "" }
}

@Composable
private fun ChatClaudeUsageBanner(usage: ClaudeUsage?, contextWindowUsage: Double? = null, modelName: String? = null) {
    val fh = usage?.fiveHour
    val sd = usage?.sevenDay

    // Model name label
    modelName?.let {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = it,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fh?.let {
                Text(
                    text = "5h ${it.utilization.toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = chatUsageColor(it.utilization),
                )
            }
            sd?.let {
                Text(
                    text = "7d ${it.utilization.toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = chatUsageColor(it.utilization),
                )
            }
            contextWindowUsage?.let { pct ->
                Text(
                    text = "ctx ${pct.toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = chatUsageColor(pct),
                )
            }
        }
        fh?.let {
            val countdown = chatResetCountdown(it.resetsAt)
            if (countdown.isNotEmpty()) {
                Text(
                    text = "resets $countdown",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    fh?.let {
        LinearProgressIndicator(
            progress = { (it.utilization / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = chatUsageColor(it.utilization),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
