package com.agentshell.feature.chat

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.SystemRepository
import com.agentshell.data.remote.acpRespondPermission
import com.agentshell.data.remote.getSessionCwd
import com.agentshell.data.remote.acpSendPrompt
import com.agentshell.data.remote.sendFileToAcpChat
import com.agentshell.data.remote.sendFileToChat
import com.agentshell.data.remote.sendChatMessage
import com.agentshell.data.remote.sendInputViaTmux
import com.agentshell.data.remote.acpClearHistory
import com.agentshell.data.remote.acpResumeSession
import com.agentshell.data.remote.clearChatLog
import com.agentshell.data.remote.loadMoreChatHistory
import com.agentshell.data.remote.selectBackend
import com.agentshell.data.remote.unwatchChatLog
import com.agentshell.data.remote.watchAcpChatLog
import com.agentshell.data.remote.watchChatLog
import com.agentshell.data.repository.ChatRepository
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.services.AudioPlayerManager
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.WhisperService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

// ---------------------------------------------------------------------------
// State model
// ---------------------------------------------------------------------------

data class PermissionOption(val id: String, val label: String)

data class PendingPermission(
    val requestId: String,
    val tool: String,
    val command: String,
    val options: List<PermissionOption> = emptyList(),
)

data class AttachedFile(
    val uri: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

private data class PendingAcpChunk(
    val sessionId: String,
    val isThinking: Boolean,
    val content: StringBuilder = StringBuilder(),
)

data class ChatUiState(
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isStreaming: Boolean = false,
    val pendingPermission: PendingPermission? = null,
    val sessionName: String = "",
    val windowIndex: Int = 0,
    val isAcp: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val isTranscribing: Boolean = false,
    val transcribedText: String? = null,
    val attachedFile: AttachedFile? = null,
    val isUploading: Boolean = false,
    val totalMessageCount: Int = 0,
    val hasMoreMessages: Boolean = false,
    val showThinking: Boolean = true,
    val showToolCalls: Boolean = true,
    val draftMessage: String = "",
    val error: String? = null,
    val fileBaseUrl: String = "",
    val sessionCwd: String = "",
    val detectedTool: String? = null,
    val contextWindowUsage: Double? = null,
    val modelName: String? = null,
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val hostRepository: HostRepository,
    private val dataStore: PreferencesDataStore,
    private val audioService: AudioService,
    private val whisperService: WhisperService,
    private val app: Application,
    private val systemRepository: SystemRepository,
    val audioPlayerManager: AudioPlayerManager,
) : ViewModel() {

    val claudeUsage = systemRepository.claudeUsage
    val codexUsage = systemRepository.codexUsage

    private val webSocketService: WebSocketService get() = chatRepository.webSocketService

    // Nav args available immediately — no race with LaunchedEffect
    private val navSessionName: String = savedStateHandle["sessionName"] ?: ""
    private val navWindowIndex: Int = savedStateHandle["windowIndex"] ?: 0
    private val navIsAcp: Boolean = savedStateHandle["isAcp"] ?: false
    private val navCwd: String = savedStateHandle["cwd"] ?: ""

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionName = navSessionName,
            windowIndex = navWindowIndex,
            isAcp = navIsAcp,
            sessionCwd = navCwd,
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _chatCleared = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val chatCleared: SharedFlow<Boolean> = _chatCleared.asSharedFlow()

    private val _cwdResult = MutableSharedFlow<String>(replay = 1)

    private var messageCollectionJob: Job? = null
    private var pendingAcpChunk: PendingAcpChunk? = null
    private var acpChunkFlushJob: Job? = null
    private var hasSeenConnectedState = false
    private var shouldResubscribeOnReconnect = false

    private fun newMessageList(messages: List<ChatMessage> = emptyList()): SnapshotStateList<ChatMessage> =
        mutableStateListOf<ChatMessage>().apply { addAll(messages) }

    private fun replaceMessages(messages: List<ChatMessage>) {
        val target = _uiState.value.messages
        target.clear()
        target.addAll(messages)
    }

    private fun prependMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        _uiState.value.messages.addAll(0, messages)
    }

    private fun rawAcpSessionId(sessionName: String): String = sessionName.removePrefix("acp_")

    private fun logDebug(message: String) {
        Log.d("ChatViewModel", message)
    }

    private fun directBackendForSession(sessionId: String): String =
        if (rawAcpSessionId(sessionId).startsWith("codex:")) "codex" else "opencode"

    private fun matchesAcpSession(messageSessionId: String, currentSessionName: String): Boolean {
        val rawStateSessionId = rawAcpSessionId(currentSessionName)
        return messageSessionId == currentSessionName ||
            messageSessionId == rawStateSessionId ||
            "acp_$messageSessionId" == currentSessionName
    }

    private fun clearPendingAcpChunk() {
        acpChunkFlushJob?.cancel()
        acpChunkFlushJob = null
        pendingAcpChunk = null
    }

    private fun flushPendingAcpChunk() {
        acpChunkFlushJob?.cancel()
        acpChunkFlushJob = null
        val pending = pendingAcpChunk ?: return
        pendingAcpChunk = null
        applyAcpMessageChunk(
            sessionId = pending.sessionId,
            content = pending.content.toString(),
            isThinking = pending.isThinking,
        )
    }

    private fun enqueueAcpChunk(sessionId: String, content: String, isThinking: Boolean) {
        val pending = pendingAcpChunk
        if (pending != null &&
            (pending.sessionId != sessionId || pending.isThinking != isThinking)
        ) {
            flushPendingAcpChunk()
        }

        val current = pendingAcpChunk
        if (current == null) {
            pendingAcpChunk = PendingAcpChunk(
                sessionId = sessionId,
                isThinking = isThinking,
                content = StringBuilder(content),
            )
        } else {
            current.content.append(content)
        }

        if (acpChunkFlushJob?.isActive != true) {
            acpChunkFlushJob = viewModelScope.launch {
                delay(50)
                flushPendingAcpChunk()
            }
        }
    }

    private fun acpToolMessageId(kind: String, toolCallId: String): String {
        val normalized = toolCallId.ifBlank { UUID.randomUUID().toString() }
        return "$kind:$normalized"
    }

    private fun observeConnectionRecovery() {
        viewModelScope.launch {
            webSocketService.connectionStatus.collect { status ->
                    when (status) {
                        com.agentshell.data.model.ConnectionStatus.CONNECTED -> {
                            if (!hasSeenConnectedState) {
                                hasSeenConnectedState = true
                                logDebug("Chat connection established for session=${_uiState.value.sessionName}")
                            } else if (shouldResubscribeOnReconnect) {
                                val state = _uiState.value
                                logDebug(
                                    "Chat reconnect detected; resubscribing session=${state.sessionName} window=${state.windowIndex} isAcp=${state.isAcp} messageCount=${state.messages.size}"
                                )
                                resubscribeActiveChat("reconnected")
                            }
                        }
                        com.agentshell.data.model.ConnectionStatus.RECONNECTING,
                        com.agentshell.data.model.ConnectionStatus.OFFLINE -> {
                            if (hasSeenConnectedState) {
                                shouldResubscribeOnReconnect = true
                                logDebug("Chat connection lost; will resubscribe when connected status=${status.name} session=${_uiState.value.sessionName}")
                            }
                        }
                        com.agentshell.data.model.ConnectionStatus.CONNECTING -> Unit
                    }
                }
        }
    }

    private fun resubscribeActiveChat(reason: String) {
        val state = _uiState.value
        if (state.sessionName.isBlank()) return

        clearPendingAcpChunk()
        _uiState.update { it.copy(error = null) }

        if (state.isAcp) {
            val rawSessionId = rawAcpSessionId(state.sessionName)
            val backend = directBackendForSession(rawSessionId)
            logDebug(
                "Resubscribing ACP chat reason=$reason rawSessionId=$rawSessionId backend=$backend cwd=${state.sessionCwd.isNotBlank()}"
            )
            webSocketService.selectBackend(backend)
            if (state.sessionCwd.isNotBlank()) {
                webSocketService.acpResumeSession(rawSessionId, state.sessionCwd)
            }
            webSocketService.watchAcpChatLog(rawSessionId, limit = 30)
        } else {
            logDebug(
                "Resubscribing TMUX chat reason=$reason session=${state.sessionName} window=${state.windowIndex}"
            )
            webSocketService.watchChatLog(state.sessionName, state.windowIndex)
            webSocketService.getSessionCwd(state.sessionName)
        }

        shouldResubscribeOnReconnect = false
    }

    // -------------------------------------------------------------------------
    // Initialisation / lifecycle
    // -------------------------------------------------------------------------

    init {
        viewModelScope.launch {
            val showThinking = dataStore.showThinking.first()
            val showToolCalls = dataStore.showToolCalls.first()
            _uiState.update { it.copy(showThinking = showThinking, showToolCalls = showToolCalls) }
        }
        viewModelScope.launch {
            val host = hostRepository.getSelectedHostOnce()
            if (host != null) {
                _uiState.update { it.copy(fileBaseUrl = "${host.httpUrl}/api/chat/files") }
            }
        }
        viewModelScope.launch {
            audioService.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }
        viewModelScope.launch {
            audioService.recordingDuration.collect { seconds ->
                _uiState.update { it.copy(recordingDuration = seconds) }
            }
        }
        observeConnectionRecovery()
        collectMessages()
        viewModelScope.launch {
            webSocketService.messages.collect { message ->
                val type = message["type"] as? String ?: return@collect
                if (type == "session-cwd") {
                    val path = message["cwd"] as? String ?: return@collect
                    val messageSessionName = message["sessionName"] as? String
                    _uiState.update { state ->
                        if (messageSessionName == null || messageSessionName == state.sessionName) {
                            state.copy(sessionCwd = path)
                        } else {
                            state
                        }
                    }
                    _cwdResult.emit(path)
                }
            }
        }
    }

    /** Begin watching a TMUX chat log. */
    fun watchChatLog(sessionName: String, windowIndex: Int) {
        clearPendingAcpChunk()
        shouldResubscribeOnReconnect = false
        _uiState.update {
            it.copy(
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = false,
                isLoading = true,
                messages = newMessageList(),
                error = null,
                sessionCwd = "",
            )
        }
        logDebug("Initial TMUX chat watch session=$sessionName window=$windowIndex")
        hasSeenConnectedState = false
        restoreDraft(draftKey(sessionName, windowIndex))
        webSocketService.watchChatLog(sessionName, windowIndex)
        webSocketService.getSessionCwd(sessionName)
    }

    /** Begin watching an ACP chat session. */
    fun startAcpChat(sessionName: String, cwd: String) {
        clearPendingAcpChunk()
        shouldResubscribeOnReconnect = false
        val sessionKey = "acp_$sessionName"
        _uiState.update {
            it.copy(
                sessionName = sessionKey,
                windowIndex = 0,
                isAcp = true,
                isLoading = true,
                messages = newMessageList(),
                error = null,
                pendingPermission = null,
                sessionCwd = cwd,
            )
        }
        logDebug("Initial ACP chat watch session=$sessionName backend=${directBackendForSession(sessionName)} cwd=${cwd.isNotBlank()}")
        hasSeenConnectedState = false
        webSocketService.selectBackend(directBackendForSession(sessionName))
        if (cwd.isNotBlank()) {
            webSocketService.acpResumeSession(sessionName, cwd)
        }
        webSocketService.watchAcpChatLog(sessionName, limit = 30)
    }

    /** Stop watching the current chat log. */
    fun unwatchChatLog() {
        val state = _uiState.value
        logDebug("Stopping chat watch session=${state.sessionName} window=${state.windowIndex} isAcp=${state.isAcp}")
        shouldResubscribeOnReconnect = false
        webSocketService.unwatchChatLog()
    }

    fun refreshActiveChat(reason: String = "manual-refresh") {
        val state = _uiState.value
        if (state.sessionName.isBlank()) return

        shouldResubscribeOnReconnect = true
        if (webSocketService.isConnected) {
            logDebug(
                "Refreshing active chat immediately reason=$reason session=${state.sessionName} window=${state.windowIndex} isAcp=${state.isAcp}"
            )
            resubscribeActiveChat(reason)
        } else {
            logDebug(
                "Refresh queued until reconnect reason=$reason session=${state.sessionName} window=${state.windowIndex} isAcp=${state.isAcp}"
            )
            webSocketService.checkConnection()
        }
    }

    /** Request the session CWD and invoke [onResult] with the path once received. */
    fun navigateToFileBrowser(onResult: (String) -> Unit) {
        val state = _uiState.value
        val currentName = state.sessionName
        if (currentName.isBlank()) return
        if (state.sessionCwd.isNotBlank()) {
            onResult(state.sessionCwd)
            return
        }
        viewModelScope.launch {
            val deferred = viewModelScope.async {
                withTimeoutOrNull(3000L) { _cwdResult.first() }
            }
            webSocketService.getSessionCwd(currentName)
            val path = deferred.await() ?: "/home"
            onResult(path)
        }
    }

    // -------------------------------------------------------------------------
    // Message sending
    // -------------------------------------------------------------------------

    fun sendMessage(text: String) {
        val state = _uiState.value
        val trimmed = text.trim()

        // Guard: don't send to tmux with an empty session name
        if (!state.isAcp && state.sessionName.isBlank()) return

        // Handle file attachment upload
        val file = state.attachedFile
        if (file != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isUploading = true) }
                try {
                    val bytes = app.contentResolver
                        .openInputStream(android.net.Uri.parse(file.uri))
                        ?.readBytes()
                    if (bytes != null) {
                        val base64 = android.util.Base64.encodeToString(
                            bytes, android.util.Base64.NO_WRAP,
                        )
                        if (state.isAcp) {
                            val sessionId = state.sessionName.removePrefix("acp_")
                            webSocketService.sendFileToAcpChat(
                                sessionId, file.filename, file.mimeType, base64,
                                trimmed.ifEmpty { null }, "",
                            )
                        } else {
                            webSocketService.sendFileToChat(
                                state.sessionName, state.windowIndex,
                                file.filename, file.mimeType, base64,
                                trimmed.ifEmpty { null },
                            )
                        }
                        // Optimistically add a user message for the file send
                        val prompt = trimmed.ifEmpty { "[File: ${file.filename}]" }
                        val userMsg = buildUserMessage(prompt)
                        _uiState.value.messages.add(userMsg)
                    }
                } finally {
                    _uiState.update { it.copy(attachedFile = null, isUploading = false, draftMessage = "") }
                    saveDraft(draftKey(state.sessionName, state.windowIndex), "")
                }
            }
            return
        }

        if (trimmed.isEmpty()) return

        _uiState.update { it.copy(draftMessage = "") }
        saveDraft(draftKey(state.sessionName, state.windowIndex), "")

        if (state.isAcp) {
            // ACP: optimistic local add (backend doesn't echo user messages back)
            val userMsg = buildUserMessage(trimmed)
            _uiState.value.messages.add(userMsg)
            val rawId = state.sessionName.removePrefix("acp_")
            webSocketService.acpSendPrompt(rawId, trimmed)
        } else {
            // TMUX: use send-chat-message which persists to chat event store,
            // broadcasts to all clients (including us — shows as ChatEvent with
            // source="webhook"), then sends to tmux via send-keys.
            // No optimistic add needed — the broadcast handles it.
            webSocketService.sendChatMessage(
                sessionName = state.sessionName,
                windowIndex = state.windowIndex,
                message = trimmed,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMoreMessages || state.isLoading) return
        _uiState.update { it.copy(isLoadingMore = true) }
            webSocketService.loadMoreChatHistory(
                sessionName = state.sessionName,
                windowIndex = state.windowIndex,
                offset = state.messages.size,
            limit = 50,
        )
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    fun respondPermission(requestId: String, optionId: String) {
        if (_uiState.value.pendingPermission?.requestId != requestId) return
        webSocketService.acpRespondPermission(requestId, optionId)
        _uiState.update { it.copy(pendingPermission = null) }
    }

    // -------------------------------------------------------------------------
    // Clear chat
    // -------------------------------------------------------------------------

    fun clearChat() {
        val state = _uiState.value
        if (state.isAcp) {
            val rawId = state.sessionName.removePrefix("acp_")
            webSocketService.acpClearHistory(rawId)
        } else {
            webSocketService.clearChatLog(state.sessionName, state.windowIndex)
        }
    }

    // -------------------------------------------------------------------------
    // Draft persistence
    // -------------------------------------------------------------------------

    fun updateDraft(text: String) {
        val state = _uiState.value
        _uiState.update { it.copy(draftMessage = text) }
        saveDraft(draftKey(state.sessionName, state.windowIndex), text)
    }

    // -------------------------------------------------------------------------
    // Voice recording
    // -------------------------------------------------------------------------

    fun startVoiceRecording() {
        viewModelScope.launch { audioService.startRecording() }
    }

    fun stopVoiceRecording() {
        viewModelScope.launch {
            val path = audioService.stopRecording() ?: return@launch
            _uiState.update { it.copy(isTranscribing = true) }
            val apiKey = dataStore.openaiApiKey.first()
            val text = whisperService.transcribe(path, apiKey)
            _uiState.update { it.copy(isTranscribing = false, transcribedText = text) }
        }
    }

    fun cancelVoiceRecording() {
        viewModelScope.launch { audioService.cancelRecording() }
    }

    fun clearTranscribedText() {
        _uiState.update { it.copy(transcribedText = null) }
    }

    suspend fun isVoiceAutoEnter(): Boolean = dataStore.voiceAutoEnter.first()

    suspend fun isVoiceButtonVisible(): Boolean = dataStore.showVoiceButton.first()

    suspend fun getRecentChatSessions(): List<String> = dataStore.getRecentChatSessions()

    suspend fun pushRecentChatSession(key: String) = dataStore.pushRecentChatSession(key)

    // -------------------------------------------------------------------------
    // File attachment
    // -------------------------------------------------------------------------

    fun attachFile(uri: String, filename: String, mimeType: String, size: Long) {
        _uiState.update { it.copy(attachedFile = AttachedFile(uri, filename, mimeType, size)) }
    }

    fun removeAttachedFile() {
        _uiState.update { it.copy(attachedFile = null) }
    }

    private fun draftKey(sessionName: String, windowIndex: Int) =
        "chat_draft_${sessionName}_$windowIndex"

    private fun saveDraft(key: String, text: String) {
        viewModelScope.launch {
            dataStore.setDraftMessage(key, text)
        }
    }

    private fun restoreDraft(key: String) {
        viewModelScope.launch {
            val draft = dataStore.getDraftMessage(key)
            _uiState.update { it.copy(draftMessage = draft ?: "") }
        }
    }

    // -------------------------------------------------------------------------
    // Message collection loop
    // -------------------------------------------------------------------------

    private fun collectMessages() {
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            chatRepository.chatMessages.collect { message ->
                if (!chatRepository.isChatMessage(message)) return@collect
                when (val type = message["type"] as? String) {
                    "chat-history"       -> handleChatHistory(message)
                    "chat-history-chunk" -> handleChatHistoryChunk(message)
                    "chat-event"         -> handleChatEvent(message)
                    "chat-file-message"  -> handleChatFileMessage(message)
                    "chat-log-error"     -> handleChatError(message)
                    "chat-log-cleared"   -> handleChatLogCleared(message)
                    "context-window-update" -> {
                        val pct = (message["contextWindowUsage"] as? Number)?.toDouble()
                        val model = message["modelName"] as? String
                        _uiState.update {
                            it.copy(
                                contextWindowUsage = pct ?: it.contextWindowUsage,
                                modelName = model ?: it.modelName,
                            )
                        }
                    }
                    "acp-message-chunk"  -> handleAcpMessageChunk(message)
                    "acp-tool-call"      -> handleAcpToolCall(message)
                    "acp-tool-result"    -> handleAcpToolResult(message)
                    "acp-permission-request" -> handleAcpPermissionRequest(message)
                    "acp-prompt-done"    -> handleAcpPromptDone(message)
                    "acp-error"          -> {
                        flushPendingAcpChunk()
                        _uiState.update { it.copy(error = message["message"]?.toString()) }
                    }
                    "acp-history-loaded" -> handleAcpHistoryLoaded(message)
                    else                 -> { /* unhandled type — ignore */ }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Message handlers
    // -------------------------------------------------------------------------

    private fun handleChatHistory(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val parsed = messagesData.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
        }
        val totalCount = (message["totalCount"] as? Number)?.toInt() ?: parsed.size
        val hasMore = message["hasMore"] as? Boolean ?: false

        val tool = (message["tool"] as? String)?.lowercase()
        val ctxUsage = (message["contextWindowUsage"] as? Number)?.toDouble()
        val model = message["modelName"] as? String

        logDebug("Received chat history session=$sessionName window=$windowIndex count=${parsed.size} total=$totalCount hasMore=$hasMore")
        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                totalMessageCount = totalCount,
                hasMoreMessages = hasMore,
                detectedTool = tool ?: it.detectedTool,
                contextWindowUsage = ctxUsage,
                modelName = model ?: it.modelName,
            )
        }
        replaceMessages(parsed)

        when (tool) {
            "claude" -> systemRepository.requestClaudeUsage()
            "codex" -> systemRepository.requestCodexUsage()
        }
    }

    private fun handleChatHistoryChunk(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val newMessages = messagesData.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
        }
        val hasMore = message["hasMore"] as? Boolean ?: false

        prependMessages(newMessages)
        _uiState.update { it.copy(isLoadingMore = false, hasMoreMessages = hasMore) }
    }

    private fun handleChatEvent(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        logDebug("Received chat event session=$sessionName window=$windowIndex source=${message["source"]}")

        @Suppress("UNCHECKED_CAST")
        val msgData = message["message"] as? Map<String, Any?> ?: return
        val source = message["source"] as? String
        val msg = parseMessage(msgData)

        // Skip user messages from backend for live tmux events (already added locally).
        if (msg.messageType == ChatMessageType.USER && source != "webhook") return

        val messages = _uiState.value.messages
        if (messages.isNotEmpty() &&
            messages.last().messageType == ChatMessageType.ASSISTANT &&
            msg.messageType == ChatMessageType.ASSISTANT
        ) {
            val lastMsg = messages.last()
            messages[messages.size - 1] = lastMsg.copy(
                blocks = lastMsg.blocks + msg.blocks,
                content = mergeContent(lastMsg.content, msg.content),
            )
        } else {
            messages.add(msg)
        }
    }

    private fun handleChatFileMessage(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        @Suppress("UNCHECKED_CAST")
        val msgData = message["message"] as? Map<String, Any?> ?: return
        val msg = parseMessage(msgData)

        if (isDuplicateFileMessage(_uiState.value.messages, msg)) return
        _uiState.value.messages.add(msg)
    }

    private fun handleChatError(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val error = message["error"] as? String ?: "Unknown error"
        _uiState.update { it.copy(error = error, isLoading = false) }
    }

    private fun handleChatLogCleared(message: Map<String, Any?>) {
        clearPendingAcpChunk()
        val sessionName = message["sessionName"] as? String ?: return
        val windowIndex = (message["windowIndex"] as? Number)?.toInt() ?: 0
        val success = message["success"] as? Boolean ?: false

        val state = _uiState.value
        // ACP sessions: backend sends "acp_<id>" as sessionName with windowIndex 0
        val matches = if (state.isAcp) {
            sessionName == state.sessionName || sessionName == "acp_${state.sessionName}"
        } else {
            sessionName == state.sessionName && windowIndex == state.windowIndex
        }
        if (!matches) return

        if (success) {
            _uiState.update {
                ChatUiState(
                    sessionName = it.sessionName,
                    windowIndex = it.windowIndex,
                    isAcp = it.isAcp,
                    showThinking = it.showThinking,
                    showToolCalls = it.showToolCalls,
                    fileBaseUrl = it.fileBaseUrl,
                    sessionCwd = it.sessionCwd,
                )
            }
            _chatCleared.tryEmit(true)
        } else {
            val error = message["error"] as? String ?: "Failed to clear chat"
            _uiState.update { it.copy(error = error, isLoading = false) }
            _chatCleared.tryEmit(false)
        }
    }

    private fun handleAcpMessageChunk(message: Map<String, Any?>) {
        val sessionId = message["sessionId"] as? String ?: return
        val state = _uiState.value
        if (!matchesAcpSession(sessionId, state.sessionName)) return

        val content = message["content"] as? String ?: ""
        val isThinking = message["isThinking"] as? Boolean ?: false
        logDebug("Received ACP chunk sessionId=$sessionId currentSession=${state.sessionName} isThinking=$isThinking contentLength=${content.length}")
        if (content.isEmpty()) return

        enqueueAcpChunk(sessionId, content, isThinking)
    }

    private fun applyAcpMessageChunk(sessionId: String, content: String, isThinking: Boolean) {
        val state = _uiState.value
        if (!matchesAcpSession(sessionId, state.sessionName) || content.isEmpty()) return
        val messages = state.messages

        if (isThinking) {
            // Merge consecutive thinking chunks into the same thinking block
            if (messages.isNotEmpty() &&
                messages.last().messageType == ChatMessageType.ASSISTANT &&
                messages.last().blocks.size == 1 &&
                messages.last().blocks.first().blockType == ChatBlockType.THINKING
            ) {
                val lastMsg = messages.last()
                val merged = (lastMsg.blocks.first().content ?: "") + content
                messages[messages.size - 1] = lastMsg.copy(
                    blocks = listOf(lastMsg.blocks.first().copy(content = merged))
                )
            } else {
                messages.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        type = "assistant",
                        timestamp = System.currentTimeMillis(),
                        blocks = listOf(ChatBlock(type = "thinking", content = content)),
                    )
                )
            }
        } else {
            // Merge consecutive text chunks into the same assistant message
            if (messages.isNotEmpty() &&
                messages.last().messageType == ChatMessageType.ASSISTANT &&
                messages.last().blocks.isEmpty()
            ) {
                val lastMsg = messages.last()
                messages[messages.size - 1] = lastMsg.copy(
                    content = (lastMsg.content ?: "") + content,
                    isStreaming = true,
                )
            } else {
                messages.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        type = "assistant",
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true,
                    )
                )
            }
        }
        if (!state.isStreaming) {
            _uiState.update { it.copy(isStreaming = true) }
        }
    }

    private fun handleAcpToolCall(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionId = message["sessionId"] as? String ?: return
        if (!matchesAcpSession(sessionId, _uiState.value.sessionName)) return

        val toolCallId = message["toolCallId"] as? String ?: ""
        val title = message["title"] as? String ?: "Unknown Tool"
        val kind = message["kind"] as? String ?: ""
        val inputStr = message["input"] as? String ?: ""

        val inputMap: Map<String, String>? = if (inputStr.isNotEmpty()) {
            try {
                val json = JSONObject(inputStr)
                json.keys().asSequence().associateWith { json.optString(it) }
            } catch (_: Exception) {
                mapOf("raw" to inputStr)
            }
        } else null

        val chatMessage = ChatMessage(
            id = acpToolMessageId("tool_call", toolCallId),
            type = "tool_call",
            blocks = listOf(
                ChatBlock(type = "tool_call", toolName = title, summary = kind, input = inputMap)
            ),
            timestamp = System.currentTimeMillis(),
        )
        _uiState.value.messages.add(chatMessage)
    }

    private fun handleAcpToolResult(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionId = message["sessionId"] as? String ?: return
        if (!matchesAcpSession(sessionId, _uiState.value.sessionName)) return

        val toolCallId = message["toolCallId"] as? String ?: ""
        val status = message["status"] as? String ?: ""
        val output = message["output"] as? String ?: ""

        val chatMessage = ChatMessage(
            id = acpToolMessageId("tool_result", toolCallId),
            type = "tool_result",
            blocks = listOf(
                ChatBlock(type = "tool_result", toolName = "", content = output, summary = status)
            ),
            timestamp = System.currentTimeMillis(),
        )
        _uiState.value.messages.add(chatMessage)
    }

    private fun handleAcpPermissionRequest(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val requestId = message["requestId"] as? String ?: ""
        val tool = message["tool"] as? String ?: "Unknown"
        val command = message["command"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val rawOptions = message["options"] as? List<*>
        val options: List<PermissionOption> = rawOptions
            ?.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                val opt = it as? Map<String, Any?> ?: return@mapNotNull null
                val id = opt["id"] as? String ?: return@mapNotNull null
                val label = opt["label"] as? String ?: id
                PermissionOption(id = id, label = label)
            }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                PermissionOption("approve", "Approve"),
                PermissionOption("deny", "Deny"),
            )

        _uiState.update {
            it.copy(
                pendingPermission = PendingPermission(
                    requestId = requestId,
                    tool = tool,
                    command = command,
                    options = options,
                )
            )
        }
    }

    private fun handleAcpPromptDone(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionId = message["sessionId"] as? String ?: return
        val state = _uiState.value
        if (!matchesAcpSession(sessionId, state.sessionName)) return

        val stopReason = message["stopReason"] as? String ?: ""

        val doneMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = "system",
            content = "Done (reason: $stopReason)",
            timestamp = System.currentTimeMillis(),
        )
        val messages = _uiState.value.messages
        for (index in messages.indices) {
            val current = messages[index]
            if (current.isStreaming) {
                messages[index] = current.copy(isStreaming = false)
            }
        }
        messages.add(doneMsg)
        _uiState.update { it.copy(isStreaming = false) }
    }

    private fun handleAcpHistoryLoaded(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionId = message["sessionId"] as? String ?: return
        val state = _uiState.value
        if (!matchesAcpSession(sessionId, state.sessionName)) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val parsed = messagesData.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
        }
        val hasMore = message["hasMore"] as? Boolean ?: false

        replaceMessages(parsed)
        _uiState.update { it.copy(isLoading = false, hasMoreMessages = hasMore) }
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private fun parseMessage(data: Map<String, Any?>): ChatMessage {
        val role = data["role"] as? String ?: "assistant"
        val blocksData = data["blocks"] as? List<*> ?: emptyList<Any>()

        val blocks = blocksData.mapNotNull { b ->
            @Suppress("UNCHECKED_CAST")
            val block = b as? Map<String, Any?> ?: return@mapNotNull null
            val blockType = block["type"] as? String ?: "text"
            when (blockType) {
                "tool_call" -> ChatBlock(
                    type = "tool_call",
                    toolName = block["name"] as? String,
                    summary = block["summary"] as? String,
                    input = parseInputMap(block["input"]),
                )
                "tool_result" -> ChatBlock(
                    type = "tool_result",
                    toolName = block["toolName"] as? String,
                    content = block["content"] as? String,
                    summary = block["summary"] as? String,
                )
                "thinking" -> ChatBlock(
                    type = "thinking",
                    content = block["content"] as? String ?: "",
                )
                "image" -> ChatBlock(
                    type = "image",
                    id = block["id"] as? String ?: "",
                    mimeType = block["mimeType"] as? String ?: "",
                    altText = block["altText"] as? String,
                )
                "audio" -> ChatBlock(
                    type = "audio",
                    id = block["id"] as? String ?: "",
                    mimeType = block["mimeType"] as? String ?: "",
                    durationSeconds = (block["durationSeconds"] as? Number)?.toDouble(),
                )
                "file" -> ChatBlock(
                    type = "file",
                    id = block["id"] as? String ?: "",
                    filename = block["filename"] as? String ?: "",
                    mimeType = block["mimeType"] as? String ?: "",
                    sizeBytes = (block["sizeBytes"] as? Number)?.toLong(),
                )
                else -> ChatBlock(type = "text", text = block["text"] as? String ?: "")
            }
        }

        val content: String
        val type: String

        if (role == "user") {
            content = blocks.filter { it.blockType == ChatBlockType.TEXT }
                .joinToString("\n") { it.text ?: "" }
            type = "user"
        } else {
            content = blocks.filter { it.blockType == ChatBlockType.TEXT }
                .joinToString("\n") { it.text ?: "" }
            type = when {
                blocks.any { it.blockType == ChatBlockType.TOOL_CALL } -> "tool_call"
                blocks.any { it.blockType == ChatBlockType.TOOL_RESULT } -> "tool_result"
                else -> "assistant"
            }
        }

        val timestampRaw = data["timestamp"] as? String
        val timestamp = timestampRaw?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: System.currentTimeMillis()

        return ChatMessage(
            id = UUID.randomUUID().toString(),
            type = type,
            content = content.ifEmpty { null },
            timestamp = timestamp,
            blocks = blocks,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseInputMap(value: Any?): Map<String, String>? {
        val map = value as? Map<*, *> ?: return null
        return map.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    private fun buildUserMessage(text: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        type = "user",
        content = text,
        timestamp = System.currentTimeMillis(),
        blocks = listOf(ChatBlock(type = "text", text = text)),
    )

    private fun mergeContent(left: String?, right: String?): String {
        val a = left?.trim() ?: ""
        val b = right?.trim() ?: ""
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> "$a\n$b"
        }
    }

    private fun isDuplicateFileMessage(
        existing: List<ChatMessage>,
        incoming: ChatMessage,
    ): Boolean {
        val incomingIds = incoming.blocks
            .filter { it.blockType == ChatBlockType.IMAGE || it.blockType == ChatBlockType.AUDIO || it.blockType == ChatBlockType.FILE }
            .mapNotNull { it.id }
            .toSet()
        if (incomingIds.isEmpty()) return false
        return existing.any { msg ->
            msg.blocks.any { block ->
                (block.blockType == ChatBlockType.IMAGE || block.blockType == ChatBlockType.AUDIO || block.blockType == ChatBlockType.FILE) &&
                        block.id != null && block.id in incomingIds
            }
        }
    }

    override fun onCleared() {
        clearPendingAcpChunk()
        super.onCleared()
        messageCollectionJob?.cancel()
    }
}
