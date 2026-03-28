package com.agentshell.feature.chat

import android.app.Application
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
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.WhisperService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
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
    val detectedTool: String? = null,
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
    systemRepository: SystemRepository,
) : ViewModel() {

    val claudeUsage = systemRepository.claudeUsage

    private val webSocketService: WebSocketService get() = chatRepository.webSocketService

    // Nav args available immediately — no race with LaunchedEffect
    private val navSessionName: String = savedStateHandle["sessionName"] ?: ""
    private val navWindowIndex: Int = savedStateHandle["windowIndex"] ?: 0
    private val navIsAcp: Boolean = savedStateHandle["isAcp"] ?: false

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionName = navSessionName,
            windowIndex = navWindowIndex,
            isAcp = navIsAcp,
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _chatCleared = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val chatCleared: SharedFlow<Boolean> = _chatCleared.asSharedFlow()

    private var messageCollectionJob: Job? = null

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
        collectMessages()
    }

    /** Begin watching a TMUX chat log. */
    fun watchChatLog(sessionName: String, windowIndex: Int) {
        _uiState.update {
            it.copy(
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = false,
                isLoading = true,
                messages = emptyList(),
                error = null,
            )
        }
        restoreDraft(draftKey(sessionName, windowIndex))
        webSocketService.watchChatLog(sessionName, windowIndex)
    }

    /** Begin watching an ACP chat session. */
    fun startAcpChat(sessionName: String, cwd: String) {
        val sessionKey = "acp_$sessionName"
        _uiState.update {
            it.copy(
                sessionName = sessionKey,
                windowIndex = 0,
                isAcp = true,
                isLoading = true,
                messages = emptyList(),
                error = null,
                pendingPermission = null,
            )
        }
        webSocketService.selectBackend("acp")
        webSocketService.acpResumeSession(sessionName, cwd)
        webSocketService.watchAcpChatLog(sessionName, limit = 30)
    }

    /** Stop watching the current chat log. */
    fun unwatchChatLog() {
        webSocketService.unwatchChatLog()
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
                        _uiState.update { it.copy(messages = it.messages + userMsg) }
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
            _uiState.update { it.copy(messages = it.messages + userMsg) }
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
                    "acp-message-chunk"  -> handleAcpMessageChunk(message)
                    "acp-tool-call"      -> handleAcpToolCall(message)
                    "acp-tool-result"    -> handleAcpToolResult(message)
                    "acp-permission-request" -> handleAcpPermissionRequest(message)
                    "acp-prompt-done"    -> handleAcpPromptDone(message)
                    "acp-error"          -> _uiState.update { it.copy(error = message["message"]?.toString()) }
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

        _uiState.update {
            it.copy(
                messages = parsed,
                isLoading = false,
                error = null,
                totalMessageCount = totalCount,
                hasMoreMessages = hasMore,
                detectedTool = tool ?: it.detectedTool,
            )
        }
    }

    private fun handleChatHistoryChunk(message: Map<String, Any?>) {
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

        _uiState.update {
            it.copy(
                messages = newMessages + it.messages,
                isLoadingMore = false,
                hasMoreMessages = hasMore,
            )
        }
    }

    private fun handleChatEvent(message: Map<String, Any?>) {
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        @Suppress("UNCHECKED_CAST")
        val msgData = message["message"] as? Map<String, Any?> ?: return
        val source = message["source"] as? String
        val msg = parseMessage(msgData)

        // Skip user messages from backend for live tmux events (already added locally).
        if (msg.messageType == ChatMessageType.USER && source != "webhook") return

        val messages = _uiState.value.messages.toMutableList()
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
        _uiState.update { it.copy(messages = messages) }
    }

    private fun handleChatFileMessage(message: Map<String, Any?>) {
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
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun handleChatError(message: Map<String, Any?>) {
        val error = message["error"] as? String ?: "Unknown error"
        _uiState.update { it.copy(error = error, isLoading = false) }
    }

    private fun handleChatLogCleared(message: Map<String, Any?>) {
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
        if (sessionId != state.sessionName) return

        val content = message["content"] as? String ?: ""
        val isThinking = message["isThinking"] as? Boolean ?: false
        if (content.isEmpty()) return

        val messages = state.messages.toMutableList()

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

        _uiState.update { it.copy(messages = messages, isStreaming = true) }
    }

    private fun handleAcpToolCall(message: Map<String, Any?>) {
        val sessionId = message["sessionId"] as? String ?: return
        if (sessionId != _uiState.value.sessionName) return

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
            id = toolCallId.ifEmpty { UUID.randomUUID().toString() },
            type = "tool_call",
            blocks = listOf(
                ChatBlock(type = "tool_call", toolName = title, summary = kind, input = inputMap)
            ),
            timestamp = System.currentTimeMillis(),
        )
        _uiState.update { it.copy(messages = it.messages + chatMessage) }
    }

    private fun handleAcpToolResult(message: Map<String, Any?>) {
        val sessionId = message["sessionId"] as? String ?: return
        if (sessionId != _uiState.value.sessionName) return

        val toolCallId = message["toolCallId"] as? String ?: ""
        val status = message["status"] as? String ?: ""
        val output = message["output"] as? String ?: ""

        val chatMessage = ChatMessage(
            id = toolCallId.ifEmpty { UUID.randomUUID().toString() },
            type = "tool_result",
            blocks = listOf(
                ChatBlock(type = "tool_result", toolName = "", content = output, summary = status)
            ),
            timestamp = System.currentTimeMillis(),
        )
        _uiState.update { it.copy(messages = it.messages + chatMessage) }
    }

    private fun handleAcpPermissionRequest(message: Map<String, Any?>) {
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
        val sessionId = message["sessionId"] as? String ?: return
        val state = _uiState.value
        if (state.sessionName != "acp_$sessionId") return

        val stopReason = message["stopReason"] as? String ?: ""

        val doneMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = "system",
            content = "Done (reason: $stopReason)",
            timestamp = System.currentTimeMillis(),
        )
        _uiState.update {
            it.copy(
                messages = it.messages.map { m -> m.copy(isStreaming = false) } + doneMsg,
                isStreaming = false,
            )
        }
    }

    private fun handleAcpHistoryLoaded(message: Map<String, Any?>) {
        val sessionId = message["sessionId"] as? String ?: return
        val state = _uiState.value
        if (state.sessionName != "acp_$sessionId") return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val parsed = messagesData.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
        }
        val hasMore = message["hasMore"] as? Boolean ?: false

        _uiState.update {
            it.copy(
                messages = parsed,
                isLoading = false,
                hasMoreMessages = hasMore,
            )
        }
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
        super.onCleared()
        messageCollectionJob?.cancel()
    }
}
