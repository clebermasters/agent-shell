package com.agentshell.feature.file_browser

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.LinearProgressIndicator
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.FileEntry
import com.agentshell.feature.alerts.FileInfoDialog
import com.agentshell.feature.alerts.InlineFileViewer
import com.agentshell.feature.alerts.supportsInlineFilePreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    initialPath: String = "/",
    openPath: String? = null,
    onOpenFile: (FileEntry) -> Unit,
    onNavigateUp: () -> Unit,
    viewModel: FileBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        viewModel::downloadTo,
    )
    val downloadFile: (FileEntry) -> Unit = { entry ->
        if (viewModel.prepareDownload(entry)) {
            try {
                downloadLauncher.launch(entry.name)
            } catch (_: ActivityNotFoundException) {
                viewModel.downloadPickerUnavailable()
            }
        }
    }
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        viewModel::uploadFrom,
    )
    val uploadFile: () -> Unit = {
        if (viewModel.prepareUpload()) {
            try {
                uploadLauncher.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                viewModel.uploadPickerUnavailable()
            }
        }
    }

    LaunchedEffect(state.downloadMessage) {
        state.downloadMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearDownloadMessage()
        }
    }
    LaunchedEffect(state.uploadMessage) {
        state.uploadMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearUploadMessage()
        }
    }
    var showSortSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val normalizedOpenPath = openPath?.trim()?.ifBlank { null }
    val initialDirectory = when {
        normalizedOpenPath == null -> initialPath
        normalizedOpenPath.endsWith("/") -> normalizedOpenPath.trimEnd('/')
        else -> parentDirectory(normalizedOpenPath)
    }.ifBlank { "/" }

    LaunchedEffect(initialDirectory, normalizedOpenPath) {
        viewModel.listDirectory(initialDirectory)
        if (normalizedOpenPath != null && !normalizedOpenPath.endsWith("/")) {
            viewModel.openFileByPath(normalizedOpenPath)
        }
    }

    // File viewer overlay
    state.viewer?.let { viewer ->
        if (viewer.isLoading) {
            AlertDialog(
                onDismissRequest = { viewModel.closeViewer() },
                title = { Text(viewer.entry?.name ?: "Loading…") },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.closeViewer() }) { Text("Cancel") }
                },
            )
        } else if (viewer.error != null) {
            AlertDialog(
                onDismissRequest = { viewModel.closeViewer() },
                title = { Text("Error") },
                text = { Text(viewer.error) },
                confirmButton = {
                    TextButton(onClick = { viewModel.closeViewer() }) { Text("Close") }
                },
            )
        } else if (viewer.bytes != null && viewer.entry != null) {
            val mime = (viewer.mimeType ?: "").lowercase()
            val filename = viewer.entry.name
            val dismiss = { viewModel.closeViewer() }
            when {
                supportsInlineFilePreview(mime, filename) ->
                    InlineFileViewer(
                        fileBytes = viewer.bytes,
                        filename = filename,
                        mimeType = mime,
                        size = viewer.entry.size,
                        onDismiss = dismiss,
                        onDownload = { downloadFile(viewer.entry) },
                    )
                mime.startsWith("text/") || isTextFile(filename) ->
                    TextFileViewer(
                        bytes = viewer.bytes,
                        filename = filename,
                        onDismiss = dismiss,
                        isSaving = viewer.isSaving,
                        saveError = viewer.saveError,
                        onSave = { content -> viewModel.saveFile(viewer.entry!!.path, content) },
                        onDownload = { downloadFile(viewer.entry) },
                    )
                else -> FileInfoDialog(
                    filename, viewer.entry.size, mime.ifEmpty { "unknown" }, dismiss,
                    onDownload = { downloadFile(viewer.entry) },
                )
            }
        }
    }

    state.downloadingFile?.let { filename ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDownload() },
            title = { Text("Downloading") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(filename)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) { Text("Cancel") }
            },
        )
    }

    state.uploadingFile?.let { filename ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Uploading") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(filename)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    state.uploadConflict?.let { filename ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUploadConflict,
            title = { Text("Replace existing file?") },
            text = { Text("$filename already exists in this folder. Uploading will replace its contents.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUpload) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUploadConflict) { Text("Cancel") }
            },
        )
    }

    val breadcrumbs = buildBreadcrumbs(state.currentPath)
    val filtered = if (searchQuery.isEmpty()) state.sortedEntries
    else state.sortedEntries.filter { it.name.lowercase().contains(searchQuery.lowercase()) }

    // Sort sheet
    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    SortMode.NAME_ASC to "Name A → Z",
                    SortMode.NAME_DESC to "Name Z → A",
                    SortMode.SIZE_ASC to "Size (smallest first)",
                    SortMode.SIZE_DESC to "Size (largest first)",
                    SortMode.MODIFIED_DESC to "Recently modified",
                )
                options.forEach { (mode, label) ->
                    ListItem(
                        headlineContent = { Text(label, color = if (state.sortMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        trailingContent = {
                            if (state.sortMode == mode) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { viewModel.setSortMode(mode); showSortSheet = false },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Rename dialog
    renameTarget?.let { entry ->
        var newName by remember(entry) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newName != entry.name) {
                            viewModel.renameFile(entry.path, newName)
                        }
                        renameTarget = null
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    // Delete confirmation dialog
    if (deleteTargets.isNotEmpty()) {
        val count = deleteTargets.size
        val hasDirectories = deleteTargets.any { it.isDirectory }
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text("Delete $count ${if (count == 1) "item" else "items"}?") },
            text = {
                Column {
                    if (count <= 5) {
                        deleteTargets.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(entry.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text("$count items selected", fontSize = 13.sp)
                    }
                    if (hasDirectories) {
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Color(0xFFF97316).copy(alpha = 0.1f), shape = MaterialTheme.shapes.small) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF97316), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Directories will be deleted recursively", fontSize = 12.sp, color = Color(0xFFF97316))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (deleteTargets.size == 1) {
                            val e = deleteTargets.first()
                            viewModel.deleteSingle(e.path, e.isDirectory)
                        } else {
                            viewModel.deleteSelected()
                        }
                        deleteTargets = emptyList()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel") } },
        )
    }

    // Paste override confirmation dialog
    if (state.pasteConflicts.isNotEmpty()) {
        val count = state.pasteConflicts.size
        val opLabel = if (state.clipboardMode == ClipboardMode.CUT) "move" else "copy"
        AlertDialog(
            onDismissRequest = { viewModel.dismissPasteConflicts() },
            title = { Text("Replace existing ${if (count == 1) "item" else "items"}?") },
            text = {
                Column {
                    Text(
                        "The following ${if (count == 1) "item already" else "items already"} exist at the destination:",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (count <= 5) {
                        state.pasteConflicts.forEach { conflict ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (conflict.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(conflict.fileName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text("$count items will be replaced", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFF97316).copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF97316), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Existing ${if (count == 1) "item" else "items"} will be overwritten by the $opLabel", fontSize = 12.sp, color = Color(0xFFF97316))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPaste() }) {
                    Text("Replace", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPasteConflicts() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${state.selectedPaths.size} selected") },
                    actions = {
                        IconButton(onClick = { viewModel.copySelected() }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { viewModel.cutSelected() }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                        }
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = {
                            val selectedEntries = state.entries.filter { state.selectedPaths.contains(it.path) }
                            deleteTargets = selectedEntries
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state.currentPath != initialPath && state.currentPath != "/") {
                                viewModel.navigateUp()
                            } else {
                                onNavigateUp()
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            breadcrumbs.forEachIndexed { index, segment ->
                                if (index > 0) Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(
                                    segment.label,
                                    fontSize = 14.sp,
                                    color = if (index == breadcrumbs.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.clickable { viewModel.navigateTo(segment.path) },
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = if (showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        IconButton(onClick = { viewModel.toggleHidden() }) {
                            Icon(
                                if (state.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle hidden",
                            )
                        }
                        IconButton(onClick = { viewModel.listDirectory(state.currentPath) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = uploadFile,
                    icon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    text = { Text("Upload") },
                )
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Clipboard banner
            if (state.clipboardPaths.isNotEmpty()) {
                val label = "${state.clipboardPaths.size} item${if (state.clipboardPaths.size > 1) "s" else ""} ${if (state.clipboardMode == ClipboardMode.CUT) "cut" else "copied"}"
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (state.clipboardMode == ClipboardMode.CUT) Icons.Default.ContentCut else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.pasteFiles() }) { Text("Paste here") }
                        IconButton(onClick = { viewModel.clearClipboard() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear clipboard", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (showSearch && !state.isSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter files…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                )
            }

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isNotEmpty()) "No matches for \"$searchQuery\"" else "Empty directory",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(filtered, key = { it.path }) { entry ->
                        val isSelected = state.selectedPaths.contains(entry.path)
                        FileBrowserEntry(
                            entry = entry,
                            isSelected = isSelected,
                            isSelectionMode = state.isSelectionMode,
                            onTap = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(entry.path)
                                } else if (entry.isDirectory) {
                                    viewModel.navigateTo(entry.path)
                                } else {
                                    viewModel.openFile(entry)
                                }
                            },
                            onLongPress = {
                                viewModel.toggleSelection(entry.path)
                            },
                            onCopyPath = { clipboard.setText(AnnotatedString(entry.path)) },
                            onCopyFile = { viewModel.copySingle(entry.path) },
                            onCutFile = { viewModel.cutSingle(entry.path) },
                            onDownload = { downloadFile(entry) },
                            onRename = { renameTarget = entry },
                            onDelete = { deleteTargets = listOf(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun parentDirectory(path: String): String {
    val trimmed = path.trim()
    val noTrailingSlash = if (trimmed.endsWith('/')) trimmed.dropLastWhile { it == '/' }.ifBlank { "/" } else trimmed
    val idx = noTrailingSlash.lastIndexOf('/')
    if (idx <= 0) return "/"
    return noTrailingSlash.substring(0, idx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowserEntry(
    entry: FileEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCopyPath: () -> Unit,
    onCopyFile: () -> Unit,
    onCutFile: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }

    if (showActions) {
        ModalBottomSheet(onDismissRequest = { showActions = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(entry.name, entry.isDirectory), contentDescription = null, tint = fileIconColor(entry.name, entry.isDirectory), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                if (!entry.isDirectory) {
                    ListItem(
                        headlineContent = { Text("Download") },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        modifier = Modifier.clickable { showActions = false; onDownload() },
                    )
                }
                ListItem(headlineContent = { Text("Copy") }, leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) }, modifier = Modifier.clickable { onCopyFile(); showActions = false })
                ListItem(headlineContent = { Text("Cut") }, leadingContent = { Icon(Icons.Default.ContentCut, contentDescription = null) }, modifier = Modifier.clickable { onCutFile(); showActions = false })
                ListItem(headlineContent = { Text("Copy path") }, leadingContent = { Icon(Icons.Default.Link, contentDescription = null) }, modifier = Modifier.clickable { onCopyPath(); showActions = false })
                ListItem(headlineContent = { Text("Rename") }, leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) }, modifier = Modifier.clickable { onRename(); showActions = false })
                ListItem(headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable { onDelete(); showActions = false })
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val subtitle = buildString {
        if (!entry.isDirectory) {
            append(formatSize(entry.size))
            entry.modified?.let { append("  ${formatDate(it)}") }
        }
    }

    ListTile(
        icon = if (isSelectionMode) null else fileIcon(entry.name, entry.isDirectory),
        iconColor = fileIconColor(entry.name, entry.isDirectory),
        isCheckbox = isSelectionMode,
        isChecked = isSelected,
        name = entry.name,
        subtitle = subtitle.ifEmpty { null },
        isDirectory = entry.isDirectory,
        isSymlink = entry.isSymlink,
        onTap = onTap,
        onLongPress = { showActions = true },
        isSelected = isSelected,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListTile(
    icon: ImageVector?,
    iconColor: Color,
    isCheckbox: Boolean,
    isChecked: Boolean,
    name: String,
    subtitle: String?,
    isDirectory: Boolean,
    isSymlink: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    isSelected: Boolean,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCheckbox) {
            Checkbox(checked = isChecked, onCheckedChange = { onTap() })
        } else {
            icon?.let { Icon(it, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isSymlink) {
                    Spacer(Modifier.width(4.dp))
                    Text("@", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
        }
        if (isDirectory && !isCheckbox) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

private data class BreadcrumbSegment(val label: String, val path: String)

private fun buildBreadcrumbs(path: String): List<BreadcrumbSegment> {
    val parts = path.split("/").filter { it.isNotEmpty() }
    val segments = mutableListOf(BreadcrumbSegment("/", "/"))
    var cumulative = ""
    parts.forEach { part ->
        cumulative += "/$part"
        segments.add(BreadcrumbSegment(part, cumulative))
    }
    return segments
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${"%.1f".format(bytes / 1024.0)}KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
}

private fun formatDate(dateStr: String): String {
    return try {
        val instant = java.time.Instant.parse(dateStr)
        val ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        "${months[ldt.monthValue - 1]} ${ldt.dayOfMonth}"
    } catch (_: Exception) { dateStr.take(10) }
}

private fun fileIcon(name: String, isDirectory: Boolean): ImageVector {
    if (isDirectory) return Icons.Default.Folder
    val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    return when (ext) {
        "dart","py","js","ts","rs","go","java","cpp","c","h","sh","bash","zsh" -> Icons.Default.Code
        "md","txt","rst" -> Icons.Default.Article
        "json","yaml","yml","toml","ini","conf" -> Icons.Default.Settings
        "jpg","jpeg","png","gif","webp","bmp","svg" -> Icons.Default.Image
        "mp3","wav","ogg","m4a","aac","flac" -> Icons.Default.AudioFile
        "html","htm" -> Icons.Default.Language
        else -> Icons.Default.InsertDriveFile
    }
}

private fun fileIconColor(name: String, isDirectory: Boolean): Color {
    if (isDirectory) return Color(0xFFFBBF24)
    val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    return when (ext) {
        "dart","py","js","ts","rs","go","java","cpp","c","h","sh","bash","zsh" -> Color(0xFF60A5FA)
        "json","yaml","yml","toml","ini","conf" -> Color(0xFFFB923C)
        "jpg","jpeg","png","gif","webp","bmp","svg" -> Color(0xFF4ADE80)
        "mp3","wav","ogg","m4a","aac","flac" -> Color(0xFFC084FC)
        "html","htm" -> Color(0xFF2DD4BF)
        "md" -> Color(0xFF22D3EE)
        else -> Color(0xFF9CA3AF)
    }
}

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "rst", "log", "csv", "tsv",
    "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "properties",
    "dart", "py", "js", "ts", "jsx", "tsx", "rs", "go", "java", "kt", "kts",
    "cpp", "c", "h", "hpp", "cs", "rb", "php", "swift", "scala", "zig",
    "sh", "bash", "zsh", "fish", "bat", "ps1",
    "xml", "svg", "html", "htm", "css", "scss", "sass", "less",
    "sql", "graphql", "proto",
    "makefile", "dockerfile", "gitignore", "gitattributes", "editorconfig",
    "gradle", "lock",
)

private fun isTextFile(name: String): Boolean {
    val lower = name.lowercase()
    if (lower == "makefile" || lower == "dockerfile" || lower == "rakefile") return true
    val ext = if (lower.contains('.')) lower.substringAfterLast('.') else ""
    return ext in TEXT_EXTENSIONS
}

@Composable
private fun TextFileViewer(
    bytes: ByteArray,
    filename: String,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    isSaving: Boolean = false,
    saveError: String? = null,
    onDownload: () -> Unit,
) {
    val originalText = remember(bytes) { String(bytes, Charsets.UTF_8) }
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(bytes) { mutableStateOf(originalText) }

    // Exit edit mode when save succeeds (isSaving goes false with no error)
    LaunchedEffect(isSaving, saveError) {
        if (!isSaving && saveError == null && isEditing) {
            isEditing = false
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).padding(vertical = 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isEditing) {
                        TextButton(
                            onClick = { isEditing = false; editedText = originalText },
                            enabled = !isSaving,
                        ) { Text("Cancel") }
                        TextButton(
                            onClick = { onSave?.invoke(editedText) },
                            enabled = !isSaving,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    } else {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                        if (onSave != null) {
                            IconButton(onClick = { isEditing = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                saveError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(8.dp))

                if (isEditing) {
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 200.dp, max = 500.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                        enabled = !isSaving,
                    )
                } else {
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = originalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
