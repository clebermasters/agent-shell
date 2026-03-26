package com.agentshell.feature.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ─── DebugViewModel ───────────────────────────────────────────────────────────

data class DebugLogEntry(
    val timestamp: String,
    val message: String,
    val isOutgoing: Boolean = false,
)

data class DebugUiState(
    val logs: List<DebugLogEntry> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val filterQuery: String = "",
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val wsService: WebSocketService,
) : ViewModel() {

    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    init {
        observeLogs()
        observeMessages()
        observeStatus()
    }

    private fun observeLogs() {
        viewModelScope.launch {
            wsService.logs.collect { log ->
                addEntry(log, isOutgoing = false)
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { msg ->
                val type = msg["type"] as? String ?: "unknown"
                val json = buildSimpleJson(msg)
                addEntry("[IN] $type: ${json.take(200)}", isOutgoing = false)
            }
        }
    }

    private fun observeStatus() {
        viewModelScope.launch {
            wsService.connectionStatus.collect { status ->
                _state.update { it.copy(connectionStatus = status) }
                addEntry("[STATUS] ${status.name}", isOutgoing = false)
            }
        }
    }

    fun sendRaw(json: String) {
        if (json.isBlank()) return
        addEntry("[OUT] $json", isOutgoing = true)
        try {
            val obj = org.json.JSONObject(json)
            val map = jsonToMap(obj)
            wsService.send(map)
        } catch (e: Exception) {
            addEntry("[ERR] Invalid JSON: ${e.message}", isOutgoing = false)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun setFilter(query: String) {
        _state.update { it.copy(filterQuery = query) }
    }

    private fun addEntry(message: String, isOutgoing: Boolean) {
        val ts = timeFormatter.format(Instant.now())
        val entry = DebugLogEntry(timestamp = ts, message = message, isOutgoing = isOutgoing)
        _state.update { s ->
            // Keep max 500 log entries
            val updated = if (s.logs.size >= 500) s.logs.drop(1) + entry else s.logs + entry
            s.copy(logs = updated)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildSimpleJson(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"$k\":\"${v?.toString()?.take(100)}\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonToMap(obj: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (val v = obj.get(key)) {
                org.json.JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }
}

// ─── DebugScreen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateUp: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var sendText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filteredLogs = if (state.filterQuery.isBlank()) state.logs
    else state.logs.filter { it.message.contains(state.filterQuery, ignoreCase = true) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    val statusColor = when (state.connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF22C55E)
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Color(0xFFF97316)
        ConnectionStatus.OFFLINE -> Color(0xFFEF4444)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Status indicator
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(statusColor, shape = androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(state.connectionStatus.name, fontSize = 11.sp, color = statusColor)
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear logs")
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = sendText,
                        onValueChange = { sendText = it },
                        placeholder = { Text("{\"type\":\"ping\"}") },
                        label = { Text("Send JSON") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.sendRaw(sendText)
                            sendText = ""
                        },
                        enabled = sendText.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Search / filter bar
            OutlinedTextField(
                value = state.filterQuery,
                onValueChange = { viewModel.setFilter(it) },
                placeholder = { Text("Filter messages…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (state.filterQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setFilter("") }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                singleLine = true,
            )

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filteredLogs, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    entry.timestamp,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6B7280),
                                    modifier = Modifier.width(72.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    entry.message,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = when {
                                        entry.isOutgoing -> Color(0xFF60A5FA)
                                        entry.message.startsWith("[ERR]") -> Color(0xFFEF4444)
                                        entry.message.startsWith("[STATUS]") -> Color(0xFFF97316)
                                        else -> Color(0xFFD1D5DB)
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
