package com.agentshell.data.repository

import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.acpListSessions
import com.agentshell.data.remote.requestSessions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Live, server-scoped swipe history shared by chat and terminal screens. */
@Singleton
class RecentSessionsRepository internal constructor(
    private val preferences: PreferencesDataStore,
    hosts: Flow<Host?>,
    connection: StateFlow<ConnectionStatus>,
    messages: Flow<Map<String, Any?>>,
    currentUrl: () -> String?,
    requestCatalogs: () -> Unit,
    scope: CoroutineScope,
) {
    @Inject constructor(preferences: PreferencesDataStore, hosts: HostRepository, socket: WebSocketService) : this(
        preferences, hosts.getSelectedHost(), socket.connectionStatus, socket.messages,
        { socket.currentWebSocketUrl?.substringBefore('?') },
        { socket.requestSessions(); socket.acpListSessions() },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal = _terminal.asStateFlow()
    private val _chat = MutableStateFlow<List<String>>(emptyList())
    val chat = _chat.asStateFlow()
    private var activeHost: Host? = null

    init {
        scope.launch {
            hosts.distinctUntilChangedBy { it?.let { host -> host.id to host.wsUrl } }.collectLatest { host ->
                activeHost = host
                _terminal.value = emptyList()
                _chat.value = emptyList()
                if (host == null) return@collectLatest
                coroutineScope {
                    var savedTerminal = emptyList<String>()
                    var savedChat = emptyList<String>()
                    var tmux: Set<String>? = null
                    var acp: Set<String>? = null
                    fun publish() {
                        _terminal.value = savedTerminal.filter { tmux?.contains(it) == true }
                        _chat.value = savedChat.filter {
                            if (it.startsWith("acp:")) acp?.contains(it.removePrefix("acp:")) == true
                            else tmux?.contains(it.removePrefix("tmux:").substringBefore('\u0000')) == true
                        }
                    }
                    launch { preferences.recentTerminalSessions(host.id).collect { savedTerminal = it; publish() } }
                    launch { preferences.recentChatSessions(host.id).collect { savedChat = it; publish() } }
                    connection.collectLatest { status ->
                        // Revalidate after every reconnect; persisted entries alone are not live sessions.
                        tmux = null; acp = null; publish()
                        val expectedUrl = "${host.wsUrl}/ws"
                        if (status != ConnectionStatus.CONNECTED || currentUrl() != expectedUrl) return@collectLatest
                        coroutineScope {
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                messages.collect { message ->
                                    if (currentUrl() != expectedUrl || message["_sourceServerUrl"] != expectedUrl) return@collect
                                    when (message["type"]) {
                                        "sessions-list", "session_list" -> {
                                            val sessions = message["sessions"] as? List<*> ?: return@collect
                                            tmux = sessions.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet()
                                        }
                                        "acp-sessions-listed" -> {
                                            val sessions = message["sessions"] as? List<*> ?: return@collect
                                            acp = sessions.mapNotNull { (it as? Map<*, *>)?.get("sessionId") as? String }.toSet()
                                        }
                                        "acp-session-deleted" -> {
                                            val id = message["sessionId"] as? String ?: return@collect
                                            acp = acp?.minus(id)
                                        }
                                        "session-killed" -> { requestCatalogs(); return@collect }
                                        else -> return@collect
                                    }
                                    publish()
                                    preferences.pruneRecentSessions(host.id, tmux, acp)
                                }
                            }
                            requestCatalogs()
                        }
                    }
                }
            }
        }
    }

    suspend fun visitTerminal(name: String) {
        val host = activeHost ?: return
        if (name.isNotBlank()) preferences.pushRecentTerminalSession(host.id, name)
    }

    suspend fun visitChat(key: String) {
        val host = activeHost ?: return
        if (key.isNotBlank()) preferences.pushRecentChatSession(host.id, key)
    }
}
