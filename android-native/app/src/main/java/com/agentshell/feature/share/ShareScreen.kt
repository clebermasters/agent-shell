package com.agentshell.feature.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.SharedDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    viewModel: ShareViewModel,
    onCancel: () -> Unit,
    onChoose: (ChatTarget, SharedDraft) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var search by rememberSaveable(state.id, state.hostId) { mutableStateOf("") }
    var showHosts by remember { mutableStateOf(false) }
    val chats = state.chats.filter { search.isBlank() || it.displayName.contains(search, ignoreCase = true) || it.cwd.contains(search, ignoreCase = true) }
        .sortedWith(compareBy<ChatTarget> { it.isAcp }.thenBy { it.displayName.lowercase() }.thenBy { it.windowIndex })
    BackHandler(onBack = onCancel)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Share to AgentShell") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel sharing") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Review and edit your message in the chat before sending.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Preparing shared content…", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            state.draft?.let { draft ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (draft.text.isNotBlank()) Text(draft.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        draft.attachment?.let { file ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (file.mimeType.startsWith("image/")) {
                                    AsyncImage(model = file.uri, contentDescription = "Shared image preview", modifier = Modifier.size(56.dp))
                                } else Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(file.filename, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (draft.filenames.size > 1) "${draft.filenames.size} files bundled in a ZIP attachment" else "${(file.sizeBytes / 1024).coerceAtLeast(1)} KB", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { showHosts = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.hosts.firstOrNull { it.id == state.hostId }?.name ?: "Choose a server", Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = showHosts, onDismissRequest = { showHosts = false }) {
                    state.hosts.forEach { host ->
                        DropdownMenuItem(text = { Text(host.name) }, onClick = { showHosts = false; viewModel.selectHost(host.id) })
                    }
                }
            }
            if (state.hosts.isEmpty()) Text("Configure a server in AgentShell settings, then share again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Search chats") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            state.connectionError?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::retry) { Text("Retry") }
                }
            }
            if (state.loadingChats) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (chats.isEmpty() && !state.loadingChats && state.hosts.isNotEmpty()) item {
                    Text(if (search.isNotBlank()) "No matching chats" else "No chats found on this server", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(chats, key = { it.key }) { target ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                        Row(Modifier.clickable(enabled = state.draft != null && !state.importing) { state.draft?.let { onChoose(target, it) } }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (target.isAcp) Icons.Default.SmartToy else Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(target.title.ifBlank { target.displayName }, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (target.isAcp) target.cwd.ifBlank { "Direct agent chat" } else "TMUX · window ${target.windowIndex}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open draft in ${target.displayName}", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
