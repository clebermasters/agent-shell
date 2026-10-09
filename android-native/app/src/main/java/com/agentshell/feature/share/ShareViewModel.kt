package com.agentshell.feature.share

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.core.config.BuildConfig
import com.agentshell.core.util.ShareIntentParser
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.Host
import com.agentshell.data.model.IncomingShare
import com.agentshell.data.model.SharedDraft
import com.agentshell.data.remote.SessionSocket
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.repository.SharedContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject

data class ShareUiState(
    val id: String? = null,
    val draft: SharedDraft? = null,
    val importing: Boolean = false,
    val error: String? = null,
    val hosts: List<Host> = emptyList(),
    val hostId: String? = null,
    val chats: List<ChatTarget> = emptyList(),
    val loadingChats: Boolean = false,
    val connectionError: String? = null,
)

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val hosts: HostRepository,
    private val shares: SharedContentRepository,
    private val client: OkHttpClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShareUiState(id = savedState["share_id"], error = savedState["share_error"]))
    val uiState = _uiState.asStateFlow()
    private var importJob: Job? = null
    private var socket: SessionSocket? = null
    private var messagesJob: Job? = null
    private var connectionJob: Job? = null
    private var timeoutJob: Job? = null
    private val waitingWindows = mutableSetOf<String>()
    private var receivedSessions = false
    private var receivedDirectSessions = false

    init {
        savedState.get<String>("incoming_share")?.let { value ->
            runCatching { Json.decodeFromString<IncomingShare>(value) }.onSuccess(::prepare)
        }
        viewModelScope.launch {
            hosts.ensureDefaultHosts()
            val selected = hosts.getSelectedHostOnce()
            hosts.getHosts().collect { available ->
                _uiState.update { it.copy(hosts = available) }
                if (_uiState.value.hostId == null || available.none { it.id == _uiState.value.hostId }) {
                    val host = available.firstOrNull { it.id == selected?.id } ?: available.firstOrNull()
                    host?.let { selectHost(it.id) }
                }
            }
        }
    }

    fun receive(intent: Intent) {
        if (!ShareIntentParser.isShare(intent)) return
        try {
            prepare(ShareIntentParser.parse(intent))
        } catch (error: Exception) {
            importJob?.cancel()
            val id = java.util.UUID.randomUUID().toString()
            savedState["share_id"] = id
            savedState["share_error"] = "Unable to open shared content: ${error.message ?: "Invalid share"}"
            savedState.remove<String>("incoming_share")
            _uiState.update { it.copy(id = id, draft = null, importing = false, error = savedState["share_error"]) }
        }
    }

    private fun prepare(incoming: IncomingShare) {
        importJob?.cancel()
        savedState["incoming_share"] = Json.encodeToString(incoming)
        savedState["share_id"] = incoming.id
        savedState.remove<String>("share_error")
        _uiState.update { it.copy(id = incoming.id, draft = null, importing = true, error = null) }
        importJob = viewModelScope.launch {
            try {
                val draft = shares.import(incoming)
                _uiState.update { it.copy(draft = draft, importing = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(importing = false, error = "Unable to open shared content: ${error.message ?: "Unreadable file"}") }
            }
        }
        _uiState.value.hosts.firstOrNull { it.id == _uiState.value.hostId }?.let(::connectHost)
    }

    fun selectHost(id: String) {
        val host = _uiState.value.hosts.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(hostId = id, chats = emptyList()) }
        if (_uiState.value.id != null) connectHost(host)
    }

    fun retry() {
        _uiState.value.hosts.firstOrNull { it.id == _uiState.value.hostId }?.let(::connectHost)
    }

    private fun connectHost(host: Host) {
        disposeSocket()
        receivedSessions = false
        receivedDirectSessions = false
        waitingWindows.clear()
        _uiState.update { it.copy(chats = emptyList(), loadingChats = true, connectionError = null) }
        val connection = SessionSocket(client)
        socket = connection
        messagesJob = viewModelScope.launch {
            connection.messages.collect { message ->
                when (message["type"]) {
                    "sessions-list", "session_list" -> {
                        receivedSessions = true
                        val sessions = maps(message["sessions"])
                        val names = sessions.mapNotNull { it["name"] as? String }.toSet()
                        _uiState.update { state -> state.copy(chats = state.chats.filter { it.isAcp || it.sessionName in names }) }
                        waitingWindows.clear()
                        waitingWindows.addAll(names)
                        names.forEach { connection.send(mapOf("type" to "list-windows", "sessionName" to it)) }
                        updateLoading()
                    }
                    "windows-list" -> {
                        val name = message["sessionName"] as? String ?: return@collect
                        if (!waitingWindows.remove(name)) return@collect
                        val targets = maps(message["windows"]).mapNotNull { window ->
                            val index = (window["index"] as? Number)?.toInt() ?: return@mapNotNull null
                            val label = window["name"] as? String ?: "window $index"
                            ChatTarget.create(host.id, name, index, title = "$name · $label")
                        }
                        _uiState.update { state -> state.copy(chats = state.chats.filterNot { !it.isAcp && it.sessionName == name } + targets) }
                        updateLoading()
                    }
                    "acp-sessions-listed" -> {
                        receivedDirectSessions = true
                        val targets = maps(message["sessions"]).mapNotNull { session ->
                            val id = session["sessionId"] as? String ?: return@mapNotNull null
                            ChatTarget.create(host.id, id, isAcp = true, cwd = session["cwd"] as? String ?: "", title = session["title"] as? String ?: "")
                        }
                        _uiState.update { state -> state.copy(chats = state.chats.filterNot { it.isAcp } + targets) }
                        updateLoading()
                    }
                }
            }
        }
        connectionJob = viewModelScope.launch {
            connection.isConnected.collect { connected ->
                if (connected) {
                    _uiState.update { it.copy(connectionError = null) }
                    connection.send(mapOf("type" to "list-sessions"))
                    connection.send(mapOf("type" to "acp-list-sessions"))
                }
            }
        }
        val url = "${host.httpUrl}/ws".toHttpUrl().newBuilder().apply {
            if (BuildConfig.AUTH_TOKEN.isNotEmpty()) addQueryParameter("token", BuildConfig.AUTH_TOKEN)
        }.build().toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        connection.connect(url)
        timeoutJob = viewModelScope.launch {
            delay(15_000)
            if (_uiState.value.loadingChats || !connection.isConnected.value) {
                _uiState.update { it.copy(loadingChats = false, connectionError = "Some chats could not be loaded. Check the server connection and retry.") }
            }
        }
    }

    private fun updateLoading() {
        _uiState.update { it.copy(loadingChats = !receivedSessions || !receivedDirectSessions || waitingWindows.isNotEmpty()) }
    }

    fun consume(discard: Boolean = false) {
        val id = _uiState.value.id
        importJob?.cancel()
        disposeSocket()
        savedState.remove<String>("incoming_share")
        savedState.remove<String>("share_id")
        savedState.remove<String>("share_error")
        _uiState.update { ShareUiState(hosts = it.hosts, hostId = it.hostId) }
        if (discard && id != null) viewModelScope.launch { shares.discard(id) }
    }

    private fun disposeSocket() {
        messagesJob?.cancel()
        connectionJob?.cancel()
        timeoutJob?.cancel()
        socket?.dispose()
        socket = null
    }

    override fun onCleared() { disposeSocket(); super.onCleared() }

    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
}
