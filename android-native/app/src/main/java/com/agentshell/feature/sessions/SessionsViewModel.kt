package com.agentshell.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.TmuxSession
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.acpCreateSession
import com.agentshell.data.remote.acpListSessions
import com.agentshell.data.remote.createSession
import com.agentshell.data.remote.deleteAcpSession
import com.agentshell.data.remote.killSession
import com.agentshell.data.remote.requestSessions
import com.agentshell.data.remote.selectBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val tmuxSessions: List<TmuxSession> = emptyList(),
    val acpSessions: List<AcpSession> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedBackend: String = "tmux",
    val selectedSessionIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
)

/** Emitted when a new ACP session is created so the UI can navigate to it. */
data class NewAcpSessionEvent(val sessionId: String, val cwd: String)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val wsService: WebSocketService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _newAcpSession = MutableSharedFlow<NewAcpSessionEvent>(extraBufferCapacity = 8)
    val newAcpSession: SharedFlow<NewAcpSessionEvent> = _newAcpSession.asSharedFlow()

    init {
        observeMessages()
        observeConnection()
        requestSessions()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { message ->
                when (message["type"] as? String) {
                    "sessions-list", "session_list" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["sessions"] as? List<Map<String, Any?>> ?: emptyList()
                        val sessions = rawList.map { s ->
                            TmuxSession(
                                name = s["name"] as? String ?: "",
                                attached = (s["attached"] as? Boolean) ?: false,
                                windows = (s["windows"] as? Number)?.toInt() ?: 1,
                                created = s["created"] as? String,
                            )
                        }
                        _uiState.update { it.copy(tmuxSessions = sessions, isLoading = false) }
                    }

                    "acp-sessions-listed" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["sessions"] as? List<Map<String, Any?>> ?: emptyList()
                        val sessions = rawList.map { s ->
                            AcpSession(
                                sessionId = s["sessionId"] as? String ?: "",
                                cwd = s["cwd"] as? String ?: "",
                                title = s["title"] as? String ?: "",
                                updatedAt = s["updatedAt"] as? String ?: "",
                            )
                        }
                        _uiState.update { it.copy(acpSessions = sessions, isLoading = false) }
                    }

                    "acp-session-created" -> {
                        val sessionId = message["sessionId"] as? String
                        val cwd = message["cwd"] as? String
                        if (sessionId != null && cwd != null) {
                            viewModelScope.launch {
                                delay(300)
                                requestSessions()
                            }
                            _newAcpSession.emit(NewAcpSessionEvent(sessionId, cwd))
                        }
                    }

                    "acp-session-deleted" -> {
                        val sessionId = message["sessionId"] as? String
                        if (sessionId != null) {
                            // Optimistically remove
                            _uiState.update { state ->
                                state.copy(
                                    acpSessions = state.acpSessions.filter { it.sessionId != sessionId },
                                )
                            }
                        }
                    }

                    "session-created" -> {
                        viewModelScope.launch {
                            delay(500)
                            requestSessions()
                        }
                    }
                }
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            wsService.connectionStatus.collect { status ->
                if (status == com.agentshell.data.model.ConnectionStatus.CONNECTED) {
                    requestSessions()
                }
            }
        }
    }

    fun requestSessions() {
        _uiState.update { it.copy(isLoading = true) }
        wsService.requestSessions()
        wsService.acpListSessions()
    }

    fun createSession(name: String) {
        _uiState.update { it.copy(isLoading = true) }
        wsService.createSession(name)
        viewModelScope.launch {
            delay(500)
            requestSessions()
        }
    }

    fun createAcpSession(cwd: String) {
        wsService.selectBackend("acp")
        wsService.acpCreateSession(cwd)
    }

    fun killSession(sessionName: String) {
        wsService.killSession(sessionName)
        viewModelScope.launch {
            delay(500)
            requestSessions()
        }
    }

    fun deleteAcpSession(sessionId: String) {
        wsService.selectBackend("acp")
        wsService.deleteAcpSession(sessionId)
        viewModelScope.launch {
            delay(500)
            requestSessions()
        }
    }

    fun deleteSelectedSessions() {
        val ids = _uiState.value.selectedSessionIds.toList()
        ids.forEach { id ->
            wsService.selectBackend("acp")
            wsService.deleteAcpSession(id)
        }
        viewModelScope.launch {
            delay(500)
            requestSessions()
        }
        exitSelectionMode()
    }

    fun toggleSelection(sessionId: String) {
        _uiState.update { state ->
            val newIds = state.selectedSessionIds.toMutableSet()
            if (newIds.contains(sessionId)) {
                newIds.remove(sessionId)
            } else {
                newIds.add(sessionId)
            }
            state.copy(
                selectedSessionIds = newIds,
                isSelectionMode = newIds.isNotEmpty(),
            )
        }
    }

    fun enterSelectionMode(sessionId: String) {
        _uiState.update { state ->
            state.copy(
                isSelectionMode = true,
                selectedSessionIds = setOf(sessionId),
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    fun switchBackend(backend: String) {
        _uiState.update { it.copy(selectedBackend = backend) }
    }
}
