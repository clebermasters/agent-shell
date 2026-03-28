package com.agentshell.feature.dotfiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.DotFile
import com.agentshell.data.model.DotFileType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotfilesScreen(
    onNavigateToEditor: (DotFile) -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToBrowse: () -> Unit,
    viewModel: DotfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val expandedSections = remember {
        mutableStateMapOf<DotFileType, Boolean>().apply {
            DotFileType.values().forEach { put(it, true) }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dotfiles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { viewModel.requestDotfiles() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onNavigateToTemplates, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = "Templates", modifier = Modifier.size(20.dp))
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToBrowse,
                containerColor = Color(0xFF6366F1),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Browse file", tint = Color.White)
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            state.error?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { viewModel.requestDotfiles() }) { Text("Retry") }
                    }
                }
            }

            if (state.isLoading && state.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.filesByType.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text("No dotfiles found", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to browse a custom path", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val typeOrder = listOf(
                        DotFileType.SHELL,
                        DotFileType.GIT,
                        DotFileType.VIM,
                        DotFileType.TMUX,
                        DotFileType.SSH,
                        DotFileType.OTHER,
                    )
                    typeOrder.forEach { type ->
                        val files = state.filesByType[type] ?: return@forEach
                        val isExpanded = expandedSections[type] ?: true

                        item(key = "header_${type.name}") {
                            DotfilesSectionHeader(
                                type = type,
                                fileCount = files.size,
                                isExpanded = isExpanded,
                                onToggle = { expandedSections[type] = !isExpanded },
                            )
                        }

                        if (isExpanded) {
                            items(files, key = { it.path }) { file ->
                                DotfileTile(
                                    file = file,
                                    onClick = {
                                        viewModel.selectFile(file)
                                        onNavigateToEditor(file)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DotfilesSectionHeader(
    type: DotFileType,
    fileCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val (icon, accentColor) = when (type) {
        DotFileType.SHELL -> "🐚" to Color(0xFF22C55E)
        DotFileType.GIT -> "🔀" to Color(0xFFF97316)
        DotFileType.VIM -> "✏️" to Color(0xFF16A34A)
        DotFileType.TMUX -> "🖥️" to Color(0xFF3B82F6)
        DotFileType.SSH -> "🔐" to Color(0xFFA855F7)
        DotFileType.OTHER -> "📄" to Color(0xFF6B7280)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            type.displayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "$fileCount",
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DotfileTile(file: DotFile, onClick: () -> Unit) {
    val typeColor = when (file.fileTypeEnum) {
        DotFileType.SHELL -> Color(0xFF22C55E)
        DotFileType.GIT -> Color(0xFFF97316)
        DotFileType.VIM -> Color(0xFF16A34A)
        DotFileType.TMUX -> Color(0xFF3B82F6)
        DotFileType.SSH -> Color(0xFFA855F7)
        DotFileType.OTHER -> Color(0xFF6B7280)
    }

    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = typeColor.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (!file.exists) Icons.Default.Add else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (!file.exists) Color(0xFFF97316) else typeColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.W500, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!file.exists) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = Color(0xFFF97316).copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraSmall) {
                        Text("NEW", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF97316), modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                if (!file.writable && file.exists) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
            Text(file.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatFileSize(file.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.modified?.let {
                Text(formatRelativeDate(it), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes == 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun formatRelativeDate(dateStr: String): String {
    return try {
        val instant = java.time.Instant.parse(dateStr)
        val now = java.time.Instant.now()
        val days = java.time.temporal.ChronoUnit.DAYS.between(instant, now)
        when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "${days} days ago"
            else -> {
                val ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                "${ldt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${ldt.dayOfMonth}"
            }
        }
    } catch (_: Exception) {
        dateStr.take(10)
    }
}
