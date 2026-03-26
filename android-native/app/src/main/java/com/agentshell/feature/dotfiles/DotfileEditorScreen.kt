package com.agentshell.feature.dotfiles

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.repository.DotfilesRepository
import io.noties.markwon.Markwon
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotfileEditorScreen(
    onNavigateUp: () -> Unit,
    viewModel: DotfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Read shared state from the singleton repository (set by DotfilesScreen.selectFile)
    val repoFile by viewModel.repoSelectedFile.collectAsState()
    val repoContent by viewModel.repoFileContent.collectAsState()
    val repoLoading by viewModel.repoIsLoading.collectAsState()

    val file = repoFile ?: state.selectedFile
    val fileName = file?.name ?: "Editor"
    // Use repo content (shared singleton) with local edit override
    var content by remember(repoContent) { mutableStateOf(repoContent ?: "") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val fileExt = fileName.substringAfterLast('.', "").lowercase()
    val isMarkdown = fileExt in setOf("md", "markdown")
    val isHtml = fileExt in setOf("html", "htm")
    val isPreviewable = isMarkdown || isHtml

    // Auto-show preview for previewable files on first load
    LaunchedEffect(file?.path) {
        showPreview = isPreviewable
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            snackbarHostState.showSnackbar("Saved successfully")
            viewModel.clearSaveSuccess()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar("Error: $it") }
    }

    if (showHistorySheet) {
        VersionHistorySheet(
            versions = state.versions,
            onRestore = { versionId ->
                file?.let { viewModel.restoreVersion(it.path, versionId) }
                showHistorySheet = false
            },
            onDismiss = { showHistorySheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName.ifEmpty { "Editor" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Preview toggle (only for markdown/html files)
                    if (isPreviewable) {
                        IconButton(onClick = { showPreview = !showPreview }) {
                            Icon(
                                if (showPreview) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = if (showPreview) "Edit" else "Preview",
                            )
                        }
                    }
                    IconButton(onClick = {
                        val path = file?.path ?: ""
                        if (path.isNotEmpty()) viewModel.getHistory(path)
                        showHistorySheet = true
                    }) {
                        Icon(Icons.Default.History, contentDescription = "Version history")
                    }
                    IconButton(
                        onClick = {
                            val path = file?.path ?: ""
                            if (path.isNotEmpty()) viewModel.saveFile(path, content)
                        },
                        enabled = !state.isSaving && file != null,
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (file == null && !repoLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text("No file selected", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }

        if (repoLoading || file == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Show preview or editor
        if (showPreview && isPreviewable && content != null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                if (isMarkdown) {
                    MarkdownPreview(content = content ?: "")
                } else if (isHtml) {
                    HtmlPreview(content = content ?: "")
                }
            }
        } else {
            // Text editor with line numbers
            Row(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                val lines = (content ?: "").lines()
                val scrollState = rememberScrollState()

                // Line numbers gutter
                Column(
                    modifier = Modifier
                        .width(40.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .verticalScroll(scrollState)
                        .padding(end = 4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                // Editor area
                BasicTextField(
                    value = content ?: "",
                    onValueChange = { content = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(scrollState),
                )
            }
        }
    }
}

// ── Markdown Preview ────────────────────────────────────────────────────────

@Composable
private fun MarkdownPreview(content: String) {
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onSurface.toArgb()

    AndroidView(
        factory = { context ->
            val markwon = Markwon.builder(context).build()
            android.widget.ScrollView(context).apply {
                val tv = android.widget.TextView(context).apply {
                    setPadding(48, 32, 48, 32)
                    setTextColor(textColor)
                    textSize = 15f
                    setLineSpacing(8f, 1f)
                }
                markwon.setMarkdown(tv, content)
                addView(tv)
            }
        },
        update = { scrollView ->
            val tv = scrollView.getChildAt(0) as? android.widget.TextView ?: return@AndroidView
            val markwon = Markwon.builder(scrollView.context).build()
            markwon.setMarkdown(tv, content)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ── HTML Preview ────────────────────────────────────────────────────────────

@Composable
private fun HtmlPreview(content: String) {
    val isDark = MaterialTheme.colorScheme.background.toArgb()

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(isDark)
                settings.javaScriptEnabled = false
                webViewClient = WebViewClient()
                loadDataWithBaseURL(null, wrapHtml(content, true), "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, wrapHtml(content, true), "text/html", "UTF-8", null)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun wrapHtml(content: String, isDark: Boolean): String {
    val bg = if (isDark) "#1E1E1E" else "#FFFFFF"
    val fg = if (isDark) "#D4D4D4" else "#1E293B"
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          body { background: $bg; color: $fg; font-family: sans-serif; padding: 16px; line-height: 1.6; }
          a { color: #6366F1; }
          pre, code { background: ${if (isDark) "#2D2D2D" else "#F1F5F9"}; padding: 2px 6px; border-radius: 4px; font-family: monospace; }
          pre { padding: 12px; overflow-x: auto; }
          img { max-width: 100%; }
        </style>
        </head>
        <body>$content</body>
        </html>
    """.trimIndent()
}

// ── Version History Sheet ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionHistorySheet(
    versions: List<com.agentshell.data.model.DotFileVersion>,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Version History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (versions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No versions found", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(versions.size) { index ->
                        val version = versions[index]
                        ListItem(
                            headlineContent = { Text(version.commitMessage ?: "Version ${index + 1}") },
                            supportingContent = {
                                Text(
                                    version.timestamp.take(19).replace("T", " "),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { onRestore(version.id) }) { Text("Restore") }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
