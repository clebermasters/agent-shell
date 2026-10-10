package com.agentshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.data.model.*
import com.agentshell.data.remote.SessionSocket
import com.agentshell.feature.chat.UiWidgetBlock
import com.agentshell.feature.chat.MessageBubble
import com.agentshell.data.services.AudioPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.util.UUID

private fun validCanvasEndpoint(value: String): Boolean =
    (value.startsWith("ws://") || value.startsWith("wss://")) &&
        runCatching { okhttp3.Request.Builder().url(value).build() }.isSuccess

/** Debug-only gallery. Its socket and credentials never alter host preferences. */
class UiPocActivity : ComponentActivity() {
    private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDestroy() { preferencesScope.cancel(); super.onDestroy() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val initialEndpoint = intent.getStringExtra("endpoint").orEmpty()
        val initialSample = intent.getIntExtra("sample", 0)
        val initialDark = intent.getBooleanExtra("dark", false)
        val messagePreview = intent.getBooleanExtra("message", false)
        val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = preferencesScope,
            produceFile = { java.io.File(cacheDir, "canvas-${UUID.randomUUID()}.preferences_pb") })
        val audio = AudioPlayerManager(applicationContext, com.agentshell.data.local.PreferencesDataStore(prefs))
        val docs = org.json.JSONArray(assets.open("ui-poc/demo.json").bufferedReader().use { it.readText() })
        val samples = (0 until docs.length()).map { index ->
            val doc = docs.getJSONObject(index)
            ChatBlock(type = "ui_widget", id = "demo-$index", title = doc.getString("title"), html = doc.getString("html"))
        }
        setContent {
            var dark by rememberSaveable { mutableStateOf(initialDark) }
            AgentShellTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CanvasGallery(initialEndpoint, initialSample, dark, { dark = !dark }, samples, messagePreview, audio)
                }
            }
        }
    }
}

@Composable
private fun CanvasGallery(initialEndpoint: String, initialSample: Int, dark: Boolean, toggleTheme: () -> Unit,
    samples: List<ChatBlock>, messagePreview: Boolean, audio: AudioPlayerManager) {
    val socket = remember { SessionSocket(OkHttpClient()) }
    var endpoint by rememberSaveable { mutableStateOf(initialEndpoint.takeIf(::validCanvasEndpoint).orEmpty()) }
    var address by remember { mutableStateOf(initialEndpoint) }
    var connectionDialog by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf(false) }
    var session by rememberSaveable { mutableStateOf("ui-A") }
    var selected by rememberSaveable { mutableIntStateOf(initialSample.coerceIn(0, 4)) }
    var binding by remember { mutableStateOf<ConversationBinding?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var status by remember { mutableStateOf(if (initialEndpoint.isEmpty()) "Offline showcase" else if (validCanvasEndpoint(initialEndpoint)) "Connecting…" else "Invalid address · offline showcase") }
    var reply by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val savedCards = rememberSaveableStateHolder()
    val connected by socket.isConnected.collectAsState()

    LaunchedEffect(socket) {
        socket.messages.collect { packet ->
            when (packet["type"]) {
                "chat-binding" -> if (packet["sessionName"] == session) {
                    binding = ChatBindingState.parse(packet)?.binding
                    status = if (binding == null) "Linking conversation…" else "Connected · $session"
                }
                "chat-history" -> if (packet["sessionName"] == session && packet["bindingId"] == binding?.id) {
                    messages.clear()
                    (packet["messages"] as? List<*>)?.forEach { raw ->
                        @Suppress("UNCHECKED_CAST")
                        (raw as? Map<String, Any?>)?.let { messages.add(ChatMessageParser.parse(it)) }
                    }
                }
                "chat-event" -> if (packet["sessionName"] == session && packet["bindingId"] == binding?.id) {
                    @Suppress("UNCHECKED_CAST")
                    (packet["message"] as? Map<String, Any?>)?.let { raw ->
                        val parsed = ChatMessageParser.parse(raw)
                        if (parsed.blocks.any { it.blockType == ChatBlockType.UI_WIDGET }) messages.add(parsed)
                        else if (parsed.messageType == ChatMessageType.ASSISTANT)
                            reply = parsed.content ?: parsed.blocks.mapNotNull { it.text }.joinToString("\n")
                    }
                }
                "chat-send-result" -> if (packet["requestId"] == pending) {
                    pending = null
                    status = if (packet["success"] == true) "Sent to $session" else "Action rejected"
                    if (packet["success"] != true) reply = packet["error"] as? String ?: "Reconnect and try again."
                }
            }
        }
    }
    LaunchedEffect(endpoint) { if (endpoint.isNotEmpty()) socket.connect(endpoint) }
    LaunchedEffect(connected, session) {
        if (connected) socket.send(mapOf("type" to "watch-chat-log", "sessionName" to session, "windowIndex" to 0, "limit" to 100))
        else if (endpoint.isNotEmpty()) status = "Reconnecting…"
    }
    LaunchedEffect(pending) {
        if (pending != null) {
            kotlinx.coroutines.delay(30_000)
            pending = null
            status = "Delivery not confirmed"
            reply = "Check the conversation before trying this action again."
        }
    }
    DisposableEffect(socket) { onDispose { socket.dispose() } }

    val widgets = if (endpoint.isEmpty()) samples else messages.flatMap { it.blocks }.filter { it.blockType == ChatBlockType.UI_WIDGET }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AgentShell", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Interactive canvas", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = toggleTheme) {
                Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Toggle canvas theme")
            }
            IconButton(onClick = { connectionDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Canvas connection settings")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (pending != null) "Sending action…" else status,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (endpoint.isNotEmpty()) {
                for (name in listOf("ui-A", "ui-B")) TextButton(onClick = {
                    if (session != name) { session = name; binding = null; messages.clear(); reply = ""; pending = null; selected = 0 }
                }) { Text(name) }
            } else Text("5 experiences", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            widgets.forEachIndexed { index, widget ->
                val label = when (widget.title) {
                    "Chart and table" -> "Insights"
                    "Calculator" -> "Split"
                    "Diagram" -> "Steps"
                    "3D scene" -> "3D"
                    "Map" -> "Map"
                    else -> widget.title ?: "View ${index + 1}"
                }
                FilterChip(selected = selected == index, onClick = { selected = index; reply = "" },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (widgets.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Waiting for interactive content…", style = MaterialTheme.typography.bodyMedium)
            } else {
                val block = widgets[selected.coerceIn(0, widgets.lastIndex)]
                savedCards.SaveableStateProvider(block.id!!) {
                    val send: (String, String) -> Unit = send@{ id, text ->
                        if (endpoint.isEmpty()) { reply = "Preview question\n$text"; return@send }
                        val linked = binding
                        if (!connected || linked == null) { reply = "Connect to this conversation before sending an action."; return@send }
                        if (pending != null) return@send
                        val request = UUID.randomUUID().toString()
                        pending = request
                        socket.send(mapOf("type" to "send-bound-ui-action", "sessionName" to session, "windowIndex" to 0,
                            "bindingId" to linked.id, "widgetId" to id, "requestId" to request, "message" to text))
                    }
                    if (messagePreview) MessageBubble(
                        message = ChatMessage(id = "message-${block.id}", type = "assistant", timestamp = 0,
                            blocks = listOf(ChatBlock(type = "text", text = "Explore the interactive view below. Maximize this message to use all of your screen."), block)),
                        audioPlayerManager = audio, onWidgetAction = send)
                    else UiWidgetBlock(block, onAction = send)
                }
            }
            if (reply.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (endpoint.isEmpty()) "Ready to ask your agent" else "Conversation response", style = MaterialTheme.typography.labelMedium)
                    Text(reply, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (connectionDialog) AlertDialog(onDismissRequest = { connectionDialog = false },
        title = { Text("Connect a test conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The offline showcase works without a server. Use an isolated test backend for live actions.")
                OutlinedTextField(address, { address = it; addressError = false }, label = { Text("WebSocket URL") },
                    singleLine = true, isError = addressError, modifier = Modifier.fillMaxWidth())
                if (addressError) Text("Enter a ws:// or wss:// URL.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (validCanvasEndpoint(address.trim())) {
                endpoint = address.trim(); binding = null; messages.clear(); connectionDialog = false
            } else addressError = true
        }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { connectionDialog = false }) { Text("Cancel") } })
}
