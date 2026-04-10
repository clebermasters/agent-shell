package com.agentshell.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.core.config.AppConfig
import com.agentshell.data.local.CommandMacroDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.CommandMacro
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.getSessionCwd
import com.agentshell.data.remote.renameSession
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.TerminalService
import com.agentshell.data.services.WhisperService
import com.agentshell.terminal.XTermController
import android.app.Application
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TerminalUiState(
    val isConnected: Boolean = false,
    val isLoading: Boolean = true,
    val sessionName: String = "",
    val windowIndex: Int = 0,
    val fontSize: Float = AppConfig.DEFAULT_FONT_SIZE,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val isTranscribing: Boolean = false,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val app: Application,
    private val terminalService: TerminalService,
    private val webSocketService: WebSocketService,
    private val audioService: AudioService,
    private val whisperService: WhisperService,
    private val prefs: PreferencesDataStore,
    private val macroDao: CommandMacroDao,
) : ViewModel() {

    private val density: Float = app.resources.displayMetrics.scaledDensity

    val macros: StateFlow<List<CommandMacro>> = macroDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    val xTermController = XTermController()

    // Last known terminal dimensions from xterm.js — used for reconnection
    private var lastCols = 80
    private var lastRows = 24

    private val _cwdResult = MutableSharedFlow<String>(replay = 1)
    val cwdResult: SharedFlow<String> = _cwdResult.asSharedFlow()

    init {
        // Observe connection status and re-attach terminal when reconnected
        viewModelScope.launch {
            webSocketService.connectionStatus.collect { status ->
                _uiState.update { it.copy(isConnected = status == ConnectionStatus.CONNECTED) }
            }
        }
        viewModelScope.launch {
            webSocketService.connectionStatus
                .map { it == ConnectionStatus.CONNECTED }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        val state = _uiState.value
                        if (state.sessionName.isNotBlank() && !state.isLoading) {
                            terminalService.attachSession(
                                state.sessionName,
                                lastCols, lastRows,
                                state.windowIndex,
                            )
                        }
                    }
                }
        }

        // Load saved font size
        viewModelScope.launch {
            val fontSize = prefs.terminalFontSize.first()
            _uiState.update { it.copy(fontSize = fontSize) }
            // If terminal already loaded, apply immediately
            if (!_uiState.value.isLoading) {
                xTermController.setFontSize(fontSize)
            }
        }

        // Observe audio recording
        viewModelScope.launch {
            audioService.recordingDuration.collect { seconds ->
                _uiState.update { it.copy(recordingDuration = seconds) }
            }
        }
        viewModelScope.launch {
            audioService.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }

        // Listen for session-cwd responses from the backend
        viewModelScope.launch {
            webSocketService.messages.collect { message ->
                val type = message["type"] as? String ?: return@collect
                if (type == "session-cwd") {
                    val path = message["cwd"] as? String ?: return@collect
                    _cwdResult.emit(path)
                }
            }
        }
    }

    /**
     * Called when xterm.js is ready (WebView loaded).
     * Attaches to the backend session and starts piping data.
     */
    fun onTerminalReady(sessionName: String, windowIndex: Int, cols: Int, rows: Int) {
        _uiState.update {
            it.copy(sessionName = sessionName, windowIndex = windowIndex, isLoading = false)
        }
        lastCols = cols
        lastRows = rows

        // Wire: TerminalService output → XTermController → xterm.js WebView
        terminalService.onTerminalData = { data ->
            xTermController.writeData(data)
        }

        // Attach to backend (triggers history + live output)
        terminalService.attachSession(sessionName, cols, rows, windowIndex)

        // Persist last session for auto-attach on next app open
        viewModelScope.launch { prefs.setLastSessionName(sessionName) }

        // Apply persisted font size to xterm.js (it starts with hardcoded default 14px)
        val fontSize = _uiState.value.fontSize
        if (fontSize != AppConfig.DEFAULT_FONT_SIZE) {
            xTermController.setFontSize(fontSize)
        }
    }

    /** Called when user types in xterm.js. */
    fun onTerminalInput(data: String) {
        terminalService.sendInput(data)
    }

    /** Called when xterm.js resizes (e.g. screen rotation, keyboard). */
    fun onTerminalResize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        terminalService.resize(cols, rows)
    }

    fun detachSession() {
        terminalService.detach()
    }

    // ── Zoom ────────────────────────────────────────────────────────────────

    fun zoomIn() {
        val newSize = (_uiState.value.fontSize + AppConfig.FONT_SIZE_STEP)
            .coerceAtMost(AppConfig.MAX_FONT_SIZE)
        _uiState.update { it.copy(fontSize = newSize) }
        // xterm.js fontSize is in CSS pixels — pass sp value directly (WebView handles scaling)
        xTermController.setFontSize(newSize)
        viewModelScope.launch { prefs.setTerminalFontSize(newSize) }
    }

    fun zoomOut() {
        val newSize = (_uiState.value.fontSize - AppConfig.FONT_SIZE_STEP)
            .coerceAtLeast(AppConfig.MIN_FONT_SIZE)
        _uiState.update { it.copy(fontSize = newSize) }
        xTermController.setFontSize(newSize)
        viewModelScope.launch { prefs.setTerminalFontSize(newSize) }
    }

    // ── Voice ───────────────────────────────────────────────────────────────

    fun startRecording() {
        viewModelScope.launch {
            audioService.startRecording()
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val path = audioService.stopRecording() ?: return@launch
            _uiState.update { it.copy(isTranscribing = true) }
            val apiKey = prefs.openaiApiKey.first()
            val text = whisperService.transcribe(path, apiKey)
            _uiState.update { it.copy(isTranscribing = false) }
            if (!text.isNullOrBlank()) {
                terminalService.sendInput(text)
            }
        }
    }

    fun cancelRecording() {
        viewModelScope.launch { audioService.cancelRecording() }
    }

    suspend fun getShowVoiceButton(): Boolean = prefs.showVoiceButton.first()

    fun setShowVoiceButton(value: Boolean) {
        viewModelScope.launch { prefs.setShowVoiceButton(value) }
    }

    // ── Session rename ────────────────────────────────────────────────────

    fun renameSession(newName: String) {
        val currentName = _uiState.value.sessionName
        if (currentName.isNotBlank() && newName.isNotBlank()) {
            webSocketService.renameSession(currentName, newName)
            _uiState.update { it.copy(sessionName = newName) }
        }
    }

    // ── Recent sessions (swipe navigation) ─────────────────────────────────

    suspend fun getRecentTerminalSessions(): List<String> = prefs.getRecentTerminalSessions()

    suspend fun pushRecentTerminalSession(name: String) = prefs.pushRecentTerminalSession(name)

    // ── Command macros ────────────────────────────────────────────────────

    fun addMacro(label: String, command: String) {
        if (label.isBlank() || command.isBlank()) return
        viewModelScope.launch { macroDao.upsert(CommandMacro(label = label.trim(), command = command.trim())) }
    }

    fun deleteMacro(macro: CommandMacro) {
        viewModelScope.launch { macroDao.delete(macro) }
    }

    // ── File browser CWD ──────────────────────────────────────────────────

    fun requestSessionCwd() {
        val currentName = _uiState.value.sessionName
        if (currentName.isNotBlank()) {
            webSocketService.getSessionCwd(currentName)
        }
    }

    /**
     * Request the session CWD and navigate to the file browser once the
     * response arrives. Handles the full async flow internally.
     */
    fun navigateToFileBrowser(onResult: (String) -> Unit) {
        val currentName = _uiState.value.sessionName
        if (currentName.isBlank()) return
        viewModelScope.launch {
            // Start collecting BEFORE sending the request to avoid race
            val deferred = viewModelScope.async {
                withTimeoutOrNull(3000L) {
                    _cwdResult.first()
                }
            }
            webSocketService.getSessionCwd(currentName)
            val path = deferred.await() ?: "/home"
            onResult(path)
        }
    }
}
