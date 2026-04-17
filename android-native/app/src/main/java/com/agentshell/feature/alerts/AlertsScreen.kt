package com.agentshell.feature.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.AppNotification
import com.agentshell.data.model.NotificationFile
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    initialNotificationId: String? = null,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var detailNotification by remember { mutableStateOf<AppNotification?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var pendingOpenNotificationId by remember(initialNotificationId) {
        mutableStateOf(initialNotificationId)
    }

    LaunchedEffect(state.notifications, pendingOpenNotificationId) {
        val targetId = pendingOpenNotificationId ?: return@LaunchedEffect
        val notification = state.notifications.firstOrNull { it.id == targetId } ?: return@LaunchedEffect
        if (!notification.read) {
            viewModel.markAsRead(notification.id)
        }
        detailNotification = notification.copy(read = true)
        pendingOpenNotificationId = null
    }

    detailNotification?.let { notification ->
        AlertDetailDialog(
            notification = notification,
            viewModel = viewModel,
            onDismiss = { detailNotification = null },
            onMarkRead = {
                viewModel.markAsRead(notification.id)
                detailNotification = null
            },
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear All Alerts") },
            text = { Text("Are you sure you want to delete all alerts? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    viewModel.deleteAllNotifications()
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                actions = {
                    if (state.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllRead() }) {
                            Text("Mark all read")
                        }
                    }
                    if (state.notifications.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all alerts")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        if (state.isLoading && state.notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NotificationsNone, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No notifications yet", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AI agents will notify you here when tasks complete",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                items(state.notifications, key = { it.id }) { notification ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteNotification(notification.id)
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onError,
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false,
                    ) {
                        Column {
                            NotificationCard(
                                notification = notification,
                                onTap = {
                                    if (!notification.read) viewModel.markAsRead(notification.id)
                                    detailNotification = notification
                                },
                                onMarkRead = { viewModel.markAsRead(notification.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    onTap: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val bgColor = if (!notification.read)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
    else
        MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread indicator
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (!notification.read) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!notification.read) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimestamp(notification.timestamp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                notification.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (notification.source.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(notification.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            if ((notification.fileCount) > 0) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                    Text("${notification.fileCount} file${if (notification.fileCount != 1) "s" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun AlertDetailDialog(
    notification: AppNotification,
    viewModel: AlertsViewModel,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    // Viewer state: which file is being viewed, the downloaded bytes
    var activeFile by remember { mutableStateOf<NotificationFile?>(null) }
    var activeFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    // Dispatch to the appropriate viewer when bytes are ready
    activeFile?.let { file ->
        activeFileBytes?.let { bytes ->
            val mime = file.mimeType.lowercase()
            val filename = file.filename
            val dismissViewer = {
                activeFile = null
                activeFileBytes = null
            }
            when {
                supportsInlineFilePreview(mime, filename) -> {
                    InlineFileViewer(
                        fileBytes = bytes,
                        filename = filename,
                        mimeType = file.mimeType,
                        size = file.size,
                        onDismiss = dismissViewer,
                    )
                }
                else -> {
                    FileInfoDialog(
                        filename = filename,
                        size = file.size,
                        mimeType = file.mimeType,
                        onDismiss = dismissViewer,
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(notification.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notification.body)
                if (notification.source.isNotEmpty()) {
                    Text("Source: ${notification.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    "Received: ${formatTimestampFull(notification.timestamp)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                notification.files?.takeIf { it.isNotEmpty() }?.let { files ->
                    HorizontalDivider()
                    Text("Attachments:", style = MaterialTheme.typography.labelMedium)
                    files.forEach { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !isDownloading) {
                                    // For unsupported types, show info without downloading
                                    val mime = file.mimeType.lowercase()
                                    val fn = file.filename
                                    val needsDownload = supportsInlineFilePreview(mime, fn)
                                    if (needsDownload) {
                                        isDownloading = true
                                        coroutineScope.launch {
                                            val bytes = viewModel.downloadFile(file.id)
                                            isDownloading = false
                                            if (bytes != null) {
                                                activeFileBytes = bytes
                                                activeFile = file
                                            }
                                        }
                                    } else {
                                        activeFileBytes = ByteArray(0)
                                        activeFile = file
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            Icon(
                                imageVector = fileIcon(file.mimeType),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                file.filename,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isDownloading && activeFile == null) {
                                // Show a small indicator while any file is downloading
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Open",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (!notification.read) {
                TextButton(onClick = onMarkRead) { Text("Mark read") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Returns an appropriate icon for a file based on its MIME type. */
private fun fileIcon(mimeType: String): androidx.compose.ui.graphics.vector.ImageVector {
    val mime = mimeType.lowercase()
    return when {
        mime.startsWith("image/") -> Icons.Default.Image
        mime.startsWith("audio/") -> Icons.Default.AudioFile
        mime == "text/markdown" -> Icons.Default.Description
        mime == "text/html" -> Icons.Default.Language
        else -> Icons.Default.Attachment
    }
}

/**
 * Bell icon composable for use in navigation bars/app bars. Shows unread count badge.
 */
@Composable
fun AlertsBellIcon(
    unreadCount: Int,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge { Text(if (unreadCount > 99) "99+" else "$unreadCount") }
            }
        },
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Notifications, contentDescription = "Alerts ($unreadCount unread)", tint = tint)
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    return try {
        val instant = Instant.ofEpochMilli(epochMillis)
        val now = Instant.now()
        val diffMin = java.time.temporal.ChronoUnit.MINUTES.between(instant, now)
        val diffHr = java.time.temporal.ChronoUnit.HOURS.between(instant, now)
        val diffDay = java.time.temporal.ChronoUnit.DAYS.between(instant, now)
        when {
            diffMin < 1 -> "just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffHr < 24 -> "${diffHr}h ago"
            diffDay < 7 -> "${diffDay}d ago"
            else -> DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(instant)
        }
    } catch (_: Exception) { "" }
}

private fun formatTimestampFull(epochMillis: Long): String {
    return try {
        val instant = Instant.ofEpochMilli(epochMillis)
        DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault()).format(instant)
    } catch (_: Exception) { "" }
}
