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
import com.agentshell.data.model.ChatMessageParser
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.ChatBindingState
import com.agentshell.data.model.acceptsConversationPacket
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.ChatReadState
import com.agentshell.data.model.ChatCursor
import com.agentshell.data.repository.ChatActivityRepository
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
import com.agentshell.data.repository.SharedContentRepository
import com.agentshell.data.repository.encodeChatAttachment
import com.agentshell.data.repository.mergeSharedText
import com.agentshell.data.services.AudioPlayerManager
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.TranscriptionQueueService
import com.agentshell.data.services.TranscriptionSuccessEvent
import com.agentshell.data.services.TranscriptionReceipt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

@Serializable
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
    val agentActivity: AgentActivity = AgentActivity(),
    val pendingPermission: PendingPermission? = null,
    val sessionName: String = "",
    val windowIndex: Int = 0,
    val isAcp: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val isTranscribing: Boolean = false,
    val transcribedText: String? = null,
    val transcriptionError: String? = null,
    val failedTranscriptionJobId: String? = null,
    val attachedFile: AttachedFile? = null,
    val isUploading: Boolean = false,
    val isSending: Boolean = false,
    val submissionNotice: String? = null,
    val bindingState: ChatBindingState? = null,
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
    val firstUnreadMessageId: String? = null,
    val readTrackingReady: Boolean = false,
    val isLocatingUnread: Boolean = false,
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val recentSessions: com.agentshell.data.repository.RecentSessionsRepository,
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val offlineCache: com.agentshell.data.repository.OfflineCacheRepository,
    private val chatActivityRepository: ChatActivityRepository,
    private val hostRepository: HostRepository,
    private val sharedContentRepository: SharedContentRepository,
    private val dataStore: PreferencesDataStore,
    private val submissions: ChatSubmissionStore,
    private val audioService: AudioService,
    private val transcriptionQueue: TranscriptionQueueService,
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
    private val navShareId: String = savedStateHandle["shareId"] ?: ""

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionName = navSessionName,
            windowIndex = navWindowIndex,
            isAcp = navIsAcp,
            sessionCwd = navCwd,
            draftMessage = savedStateHandle["share_draft_text"] ?: "",
            attachedFile = savedStateHandle.get<String>("share_attachment")?.let {
                runCatching { Json.decodeFromString<AttachedFile>(it) }.getOrNull()
            },
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _chatCleared = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val chatCleared: SharedFlow<Boolean> = _chatCleared.asSharedFlow()

    private val _cwdResult = MutableSharedFlow<String>(replay = 1)

    private var messageCollectionJob: Job? = null
    private var pendingAcpChunk: PendingAcpChunk? = null
    private var acpChunkFlushJob: Job? = null
    private val connectionRecovery = ChatConnectionRecovery()
    private var readTarget: ChatTarget? = null
    private var readState: ChatReadState? = null
    private var readHostServerUrl: String? = null
    private var readStateJob: Job? = null
    private var agentActivityJob: Job? = null
    private var visitUnreadCursor: ChatCursor? = null
    private val readOwner = UUID.randomUUID().toString()
    private var screenActive = false
    private var followingLive = false
    private val transcriptionReceipt = TranscriptionReceipt()
    private var pendingSend: ChatSubmission? = null
    private var activeSubmission: ChatSubmission? = null
    private val submissionScreenActive = MutableStateFlow(false)
    private var submissionRecoveryJob: Job? = null
    private var sendReceiptsAvailable = false
    private var lastConnectionGeneration = -1L
    private val restoredSubmissionDrafts = mutableSetOf<String>()
    private var cacheLoadJob: Job? = null
    private var cacheSaveJob: Job? = null
    private var restoringCache = false
    private var cacheLoadGeneration = 0
    private var cachedConversationKey: String? = null
    private var remoteLoadedCount = 0
    private var oldestCachedPosition: Int? = null
    private var hasOlderCachedMessages = false
    private var remoteHasMoreMessages = false
    private var cachedHistoryComplete = false
    private var remoteGapBoundaryIds: Set<String> = emptySet()
    private var cacheDirty = false
    private var lastMarkedReadId: String? = null
    private var historyPageJob: Job? = null
    private var pagingGeneration = 0
    private var requestedRemotePage = false

    private fun canLoadOlderMessages() = hasOlderCachedMessages || (webSocketService.isConnected && remoteHasMoreMessages)

    private fun resetHistoryPaging() {
        historyPageJob?.cancel()
        historyPageJob = null
        pagingGeneration += 1
        requestedRemotePage = false
        oldestCachedPosition = null
        hasOlderCachedMessages = false
        remoteHasMoreMessages = false
        cachedHistoryComplete = false
        remoteGapBoundaryIds = emptySet()
        remoteLoadedCount = 0
        lastMarkedReadId = null
    }

    private fun saveRemoteHistory(messages: List<ChatMessage>, complete: Boolean, tool: String? = null) {
        cacheSaveJob?.cancel()
        cacheSaveJob = null
        cacheDirty = false
        readTarget?.let { offlineCache.saveHistoryAsync(it, messages, complete, cachedConversationKey, tool) }
    }

    private suspend fun restoreCachedChat(generation: Int) {
        val target = readTarget
        try {
            val cached = target?.let { offlineCache.historyPage(it) }
            if (generation != cacheLoadGeneration) return
            if (cached != null) {
                cachedConversationKey = cached.conversation.conversationKey.takeIf { it.isNotBlank() }
                replaceMessages(cached.messages)
                oldestCachedPosition = cached.oldestPosition
                hasOlderCachedMessages = cached.hasMore
                cachedHistoryComplete = cached.conversation.complete
                _uiState.update { it.copy(
                    detectedTool = cached.conversation.tool,
                    sessionCwd = it.sessionCwd.ifBlank { cached.conversation.cwd },
                    totalMessageCount = cached.totalMessageCount,
                    hasMoreMessages = cached.hasMore,
                ) }
                updateUnreadBoundary()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("ChatViewModel", "Could not restore cached history", error)
        } finally {
            if (generation == cacheLoadGeneration) {
                restoringCache = false
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Coalesce streaming changes without retaining a full snapshot per protocol packet. */
    private fun persistChat(immediate: Boolean = false) {
        if (!immediate) cacheDirty = true
        if (!cacheDirty) return
        val target = readTarget ?: return
        val state = _uiState.value
        if (target.sessionName != state.sessionName.removePrefix(if (state.isAcp) "acp_" else "") || target.isAcp != state.isAcp || target.windowIndex != state.windowIndex || restoringCache) return
        fun saveCurrentSnapshot() {
            val current = _uiState.value
            offlineCache.saveHistoryAsync(target, current.messages.toList(), false, cachedConversationKey, current.detectedTool)
            cacheDirty = false
        }
        if (immediate) {
            cacheSaveJob?.cancel()
            cacheSaveJob = null
            saveCurrentSnapshot()
        } else if (cacheSaveJob?.isActive != true) {
            cacheSaveJob = viewModelScope.launch {
                delay(1000)
                if (cacheDirty && readTarget == target && !restoringCache) saveCurrentSnapshot()
                cacheSaveJob = null
            }
        }
    }

    private fun newMessageList(messages: List<ChatMessage> = emptyList()): SnapshotStateList<ChatMessage> =
        mutableStateListOf<ChatMessage>().apply { addAll(messages) }

    private fun replaceMessages(messages: List<ChatMessage>) {
        val target = _uiState.value.messages
        target.clear()
        target.addAll(messages.distinctBy { it.id })
    }

    private fun prependMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val existing = _uiState.value.messages.mapTo(mutableSetOf()) { it.id }
        _uiState.value.messages.addAll(0, messages.filter { it.id !in existing }.distinctBy { it.id })
    }

    private suspend fun prepareReadTracking(sessionName: String, windowIndex: Int, isAcp: Boolean, cwd: String = "") {
        val host = hostRepository.getSelectedHostOnce()
        if (host == null) {
            _uiState.update { it.copy(readTrackingReady = true) }
            return
        }
        readStateJob?.cancel()
        agentActivityJob?.cancel()
        chatActivityRepository.setChatOpen(readOwner, null, false)
        chatActivityRepository.setViewing(readOwner, null, false)
        val target = ChatTarget.create(host.id, sessionName, windowIndex, isAcp, cwd)
        readHostServerUrl = "${host.wsUrl}/ws"
        readTarget = target
        visitUnreadCursor = null
        readState = chatActivityRepository.register(target)
        _uiState.update { it.copy(firstUnreadMessageId = null, readTrackingReady = true, isLocatingUnread = false) }
        readStateJob = viewModelScope.launch {
            chatActivityRepository.states.map { it[target.key] }.distinctUntilChanged().collect { state ->
                readState = state
                updateUnreadBoundary()
            }
        }
        agentActivityJob = viewModelScope.launch {
            chatActivityRepository.agentActivities.map { it[target.key] ?: AgentActivity() }.distinctUntilChanged().collect { activity ->
                _uiState.update { it.copy(agentActivity = activity) }
            }
        }
        chatActivityRepository.setChatOpen(readOwner, target, screenActive)
        chatActivityRepository.setViewing(readOwner, target, screenActive && followingLive)
    }

    private fun updateUnreadBoundary() {
        val state = _uiState.value
        if (state.firstUnreadMessageId != null && state.messages.any { it.id == state.firstUnreadMessageId }) return
        if (state.firstUnreadMessageId != null) _uiState.update { it.copy(firstUnreadMessageId = null) }
        val tracking = readState ?: return
        val oldestLoaded = state.messages.firstOrNull()
        if (tracking.unread.isNotEmpty() && tracking.lastRead != null && state.hasMoreMessages &&
            oldestLoaded != null && oldestLoaded.timestamp > tracking.lastRead.timestamp &&
            state.messages.none { it.id == tracking.lastRead.id }
        ) {
            _uiState.update { it.copy(isLocatingUnread = true) }
            loadMore()
            return
        }
        if (state.isLocatingUnread) {
            val earlierCursor = tracking.unread.firstOrNull()
            if (earlierCursor != null && (visitUnreadCursor == null || earlierCursor.timestamp < visitUnreadCursor!!.timestamp)) {
                visitUnreadCursor = earlierCursor
            }
        }
        val cursor = visitUnreadCursor ?: tracking.unread.firstOrNull() ?: return
        visitUnreadCursor = cursor
        if (state.isLoading || state.messages.isEmpty()) return
        val index = tracking.copy(unread = listOf(cursor)).unreadIndex(state.messages, state.hasMoreMessages)
        if (index != null) {
            _uiState.update { it.copy(firstUnreadMessageId = state.messages[index].id, isLocatingUnread = false) }
        } else if (state.hasMoreMessages) {
            _uiState.update { it.copy(isLocatingUnread = true) }
            loadMore()
        } else {
            _uiState.update { it.copy(isLocatingUnread = false) }
        }
    }

    fun setScreenActive(active: Boolean) {
        if (screenActive && !active) {
            visitUnreadCursor = null
            _uiState.update { it.copy(firstUnreadMessageId = null) }
        }
        screenActive = active
        submissionScreenActive.value = active
        chatActivityRepository.setChatOpen(readOwner, readTarget, active)
        chatActivityRepository.setViewing(readOwner, readTarget, active && followingLive)
        if (active) {
            updateUnreadBoundary()
            if (connectionRecovery.pending && webSocketService.isConnected) {
                resubscribeActiveChat("screen resumed after reconnect")
            }
            recoverSubmissions()
        } else {
            submissionRecoveryJob?.cancel()
        }
    }

    fun setFollowingLive(following: Boolean) {
        followingLive = following
        chatActivityRepository.setViewing(readOwner, readTarget, screenActive && following)
    }

    fun markReadThrough(messageId: String) {
        if (!screenActive || !_uiState.value.readTrackingReady || _uiState.value.isLocatingUnread) return
        val target = readTarget ?: return
        if (lastMarkedReadId == messageId) return
        val messages = _uiState.value.messages.toList()
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        lastMarkedReadId = messageId
        viewModelScope.launch { chatActivityRepository.readThrough(target, messages.take(index + 1)) }
    }

    private fun reconcileReadHistory(messages: List<ChatMessage>) {
        val target = readTarget
        if (target == null) {
            _uiState.update { it.copy(readTrackingReady = true) }
            return
        }
        _uiState.update { it.copy(readTrackingReady = false) }
        viewModelScope.launch {
            chatActivityRepository.history(target, messages)
            readState = chatActivityRepository.states.value[target.key]
            updateUnreadBoundary()
            _uiState.update { it.copy(readTrackingReady = true) }
        }
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
        persistChat()
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
            combine(webSocketService.connectionStatus, webSocketService.connectionGeneration) { status, generation -> status to generation }.collect { (status, generation) ->
                val restore = connectionRecovery.onConnection(status, screenActive)
                if (status != com.agentshell.data.model.ConnectionStatus.CONNECTED) {
                    pendingSend?.let { submissions.checking(it.id, sendReceiptsAvailable) }
                } else if (screenActive && (restore || generation != lastConnectionGeneration)) {
                    lastConnectionGeneration = generation
                    resubscribeActiveChat("reconnected")
                }
            }
        }
    }

    private fun resubscribeActiveChat(reason: String) {
        val state = _uiState.value
        if (state.sessionName.isBlank()) return
        if (restoringCache || (readHostServerUrl != null && webSocketService.currentWebSocketUrl?.substringBefore('?') != readHostServerUrl)) {
            connectionRecovery.pending = true
            return
        }

        flushPendingAcpChunk()
        persistChat(immediate = true)
        historyPageJob?.cancel()
        historyPageJob = null
        pagingGeneration += 1
        requestedRemotePage = false
        oldestCachedPosition = null // Resolve the next page from its stable message ID after a refresh.
        _uiState.update { it.copy(error = null, isLoadingMore = false) }

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

        connectionRecovery.pending = false
        recoverSubmissions()
    }

    private fun observeSubmissions() {
        viewModelScope.launch {
            val scope = _uiState.map { state ->
                listOf(readHostServerUrl, state.sessionName, state.windowIndex.toString(), state.isAcp.toString(),
                    state.bindingState?.binding?.id, state.isLoading.toString(), cachedConversationKey)
            }.distinctUntilChanged()
            combine(submissions.records, scope, submissionScreenActive) { records, _, active -> records to active }.collect { (records, active) ->
                val state = _uiState.value
                val ticket = records.firstOrNull { it.serverUrl == readHostServerUrl && it.sessionName == state.sessionName &&
                    it.windowIndex == state.windowIndex && !state.isAcp &&
                    (state.bindingState?.binding?.id == null || state.bindingState.binding.id == it.bindingId) }
                pendingSend = ticket?.takeIf { it.waiting }
                activeSubmission = ticket
                _uiState.update { it.copy(isSending = ticket?.waiting == true, submissionNotice = ticket?.notice) }
                if (ticket != null && active && !restoringCache && ticket.widgetId == null &&
                    state.bindingState?.binding?.id == ticket.bindingId && restoredSubmissionDrafts.add(ticket.id)) {
                    val saved = submissions.savedDraft(ticket.draftKey)
                    _uiState.update { current ->
                        if (current.bindingState?.binding?.id == ticket.bindingId && current.draftMessage.isBlank() && current.attachedFile == null)
                            current.copy(draftMessage = saved ?: if (ticket.draftEdited) "" else ticket.text,
                                attachedFile = if (!ticket.draftEdited && (saved == null || saved == ticket.text)) ticket.file else null)
                        else current
                    }
                }
                if (ticket?.phase != "complete" || !active || restoringCache || state.bindingState?.binding?.id == null) return@collect
                val same = ticket.bindingId == state.bindingState.binding.id
                val success = ticket.success == true
                if (same) {
                    val latest = _uiState.value
                    val matchingDraft = readHostServerUrl == ticket.serverUrl && latest.bindingState?.binding?.id == ticket.bindingId &&
                        ticket.matchesDraft(latest.draftMessage, latest.attachedFile)
                    _uiState.update { current ->
                        val currentConversation = readHostServerUrl == ticket.serverUrl && current.bindingState?.binding?.id == ticket.bindingId
                        val matching = currentConversation && ticket.matchesDraft(current.draftMessage, current.attachedFile)
                        if (!currentConversation) current else current.copy(
                            draftMessage = if (success && ticket.widgetId == null && matching) "" else current.draftMessage,
                            attachedFile = if (success && ticket.widgetId == null && matching) null else current.attachedFile,
                            isSending = false, submissionNotice = null,
                            error = if (success) null else ticket.error ?: "Unable to send this draft",
                        )
                    }
                    if (success) {
                        if (matchingDraft) submissions.clearSavedDraft(ticket)
                        if (ticket.widgetId == null && matchingDraft && readHostServerUrl == ticket.serverUrl && _uiState.value.bindingState?.binding?.id == ticket.bindingId) {
                            if (navShareId.isNotBlank()) savedStateHandle["share_draft_text"] = ""
                            clearSharedAttachment()
                        }
                    }
                }
                submissions.remove(ticket.id)
                if (same && success && readHostServerUrl == ticket.serverUrl && _uiState.value.bindingState?.binding?.id == ticket.bindingId) refreshActiveChat("message delivery confirmed")
            }
        }
    }

    private fun recoverSubmissions() {
        submissionRecoveryJob?.cancel()
        if (!screenActive || !sendReceiptsAvailable || !webSocketService.isConnected) return
        val server = readHostServerUrl ?: return
        val session = _uiState.value.sessionName
        val window = _uiState.value.windowIndex
        submissionRecoveryJob = viewModelScope.launch {
            val queried = mutableSetOf<String>()
            // Only read receipts while the chat is visible. Never replay the command.
            for (pause in listOf(500L, 1_500L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 30_000L)) {
                delay(pause)
                if (!screenActive || !webSocketService.isConnected || readHostServerUrl != server) break
                val url = webSocketService.currentWebSocketUrl?.takeIf { it.substringBefore('?') == server } ?: break
                val pending = submissions.records.first().filter { it.serverUrl == server && it.sessionName == session && it.windowIndex == window && it.unresolved }
                if (pending.isEmpty()) break
                pending.forEach { queried += it.id; webSocketService.sendIfConnected(it.lookup, url) }
            }
            queried.forEach { submissions.unconfirmed(it) }
        }
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

        // Observe transcription retry successes from the queue
        viewModelScope.launch {
            transcriptionQueue.successEvents.collect { event ->
                if (event.source == com.agentshell.data.model.TranscriptionJob.Source.CHAT) {
                    receiveTranscription(event.jobId, event.text, event.sourceSessionId)
                }
            }
        }
        // One collector per ViewModel; starting another recording must not add another observer.
        viewModelScope.launch {
            transcriptionQueue.pendingResult.collect { result ->
                if (result == null || result.source != com.agentshell.data.model.TranscriptionJob.Source.CHAT) return@collect
                when (result) {
                    is TranscriptionQueueService.PendingResult.Success -> receiveTranscription(result.jobId, result.text, result.sourceSessionId)
                    is TranscriptionQueueService.PendingResult.Failed -> _uiState.update {
                        it.copy(isTranscribing = false, transcriptionError = result.error, failedTranscriptionJobId = result.jobId)
                    }
                }
                transcriptionQueue.clearPendingResult(result)
            }
        }

        observeConnectionRecovery()
        observeSubmissions()
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
        sendReceiptsAvailable = false
        submissionRecoveryJob?.cancel()
        flushPendingAcpChunk()
        persistChat(immediate = true)
        cacheLoadJob?.cancel()
        restoringCache = true
        val generation = ++cacheLoadGeneration
        cachedConversationKey = null
        resetHistoryPaging()
        agentActivityJob?.cancel()
        clearPendingAcpChunk()
        connectionRecovery.watchStarted(webSocketService.isConnected)
        _uiState.update {
            it.copy(
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = false,
                agentActivity = AgentActivity(),
                isLoading = true,
                isLoadingMore = false,
                hasMoreMessages = false,
                readTrackingReady = false,
                messages = newMessageList(),
                error = null,
                sessionCwd = "",
                bindingState = ChatBindingState("checking", "", "", null, emptyList(), "Linking this terminal…"),
            )
        }
        logDebug("Initial TMUX chat watch session=$sessionName window=$windowIndex")
        restoreDraft(draftKey(sessionName, windowIndex))
        cacheLoadJob = viewModelScope.launch {
            prepareReadTracking(sessionName, windowIndex, false)
            restoreCachedChat(generation)
            if (webSocketService.isConnected) resubscribeActiveChat("cache loaded")
        }
    }

    /** Begin watching an ACP chat session. */
    fun startAcpChat(sessionName: String, cwd: String) {
        sendReceiptsAvailable = false
        submissionRecoveryJob?.cancel()
        flushPendingAcpChunk()
        persistChat(immediate = true)
        cacheLoadJob?.cancel()
        restoringCache = true
        val generation = ++cacheLoadGeneration
        cachedConversationKey = null
        resetHistoryPaging()
        agentActivityJob?.cancel()
        clearPendingAcpChunk()
        connectionRecovery.watchStarted(webSocketService.isConnected)
        val sessionKey = "acp_$sessionName"
        _uiState.update {
            it.copy(
                sessionName = sessionKey,
                windowIndex = 0,
                isAcp = true,
                agentActivity = AgentActivity(),
                isLoading = true,
                isLoadingMore = false,
                hasMoreMessages = false,
                readTrackingReady = false,
                messages = newMessageList(),
                error = null,
                pendingPermission = null,
                sessionCwd = cwd,
                bindingState = null,
            )
        }
        logDebug("Initial ACP chat watch session=$sessionName backend=${directBackendForSession(sessionName)} cwd=${cwd.isNotBlank()}")
        restoreDraft(draftKey(sessionKey, 0))
        cacheLoadJob = viewModelScope.launch {
            prepareReadTracking(sessionName, 0, true, cwd)
            restoreCachedChat(generation)
            if (webSocketService.isConnected) resubscribeActiveChat("cache loaded")
        }
    }

    /** Stop watching the current chat log. */
    fun unwatchChatLog() {
        val state = _uiState.value
        logDebug("Stopping chat watch session=${state.sessionName} window=${state.windowIndex} isAcp=${state.isAcp}")
        connectionRecovery.pending = false
        webSocketService.unwatchChatLog()
    }

    fun refreshActiveChat(reason: String = "manual-refresh") {
        val state = _uiState.value
        if (state.sessionName.isBlank()) return

        connectionRecovery.pending = true
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
        if (state.isUploading || state.isSending) return
        if (!webSocketService.isConnected) {
            _uiState.update { it.copy(error = "You’re offline. Reconnect to send your draft") }
            return
        }
        val trimmed = text.trim()

        // Guard: don't send to tmux with an empty session name
        if (!state.isAcp && state.sessionName.isBlank()) return
        if (!state.isAcp && state.bindingState != null) {
            if (!state.bindingState.canSend) {
                _uiState.update { it.copy(error = "Waiting for this terminal’s conversation to be identified automatically") }
                return
            }
            sendBoundMessage(text, state)
            return
        }

        // Handle file attachment upload
        val file = state.attachedFile
        if (file != null) {
            val serverUrl = webSocketService.currentWebSocketUrl
            if (serverUrl == null || serverUrl.substringBefore('?') != readHostServerUrl) {
                _uiState.update { it.copy(error = "Wait for this chat's server connection before sending") }
                return
            }
            _uiState.update { it.copy(isUploading = true, error = null) }
            viewModelScope.launch {
                try {
                    val base64 = withContext(Dispatchers.IO) {
                        encodeChatAttachment(app.contentResolver.openInputStream(android.net.Uri.parse(file.uri)))
                    }
                    val sent = if (state.isAcp) {
                        val sessionId = state.sessionName.removePrefix("acp_")
                        webSocketService.sendFileToAcpChat(
                            sessionId, file.filename, file.mimeType, base64,
                            trimmed.ifEmpty { null }, state.sessionCwd, serverUrl,
                        )
                    } else {
                        webSocketService.sendFileToChat(
                            state.sessionName, state.windowIndex,
                            file.filename, file.mimeType, base64,
                            trimmed.ifEmpty { null }, serverUrl,
                        )
                    }
                    if (!sent) throw java.io.IOException("Unable to send the attachment. Check your server connection and retry")
                    // Optimistically add a user message for the file send.
                    val prompt = trimmed.ifEmpty { "[File: ${file.filename}]" }
                    val userMsg = buildUserMessage(prompt)
                    _uiState.value.messages.add(userMsg)
                    _uiState.update { it.copy(attachedFile = null, isUploading = false, draftMessage = "") }
                    if (navShareId.isNotBlank()) savedStateHandle["share_draft_text"] = ""
                    saveDraft(draftKey(state.sessionName, state.windowIndex), "")
                    clearSharedAttachment()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _uiState.update { it.copy(error = error.message ?: "Unable to send the attachment") }
                } finally {
                    _uiState.update { it.copy(isUploading = false) }
                }
            }
            return
        }

        if (trimmed.isEmpty()) return

        _uiState.update { it.copy(draftMessage = "") }
        if (navShareId.isNotBlank()) savedStateHandle["share_draft_text"] = ""
        saveDraft(draftKey(state.sessionName, state.windowIndex), "")
        clearSharedAttachment()

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

    fun sendWidgetAction(widgetId: String, text: String) {
        val state = _uiState.value
        if (state.isSending || state.isUploading || state.bindingState?.canSend != true) return
        sendBoundMessage(text, state.copy(attachedFile = null), widgetId)
    }

    private fun sendBoundMessage(text: String, state: ChatUiState, widgetId: String? = null) {
        val binding = state.bindingState?.binding ?: return
        if (text.isBlank() && state.attachedFile == null) return
        val server = webSocketService.currentWebSocketUrl
        if (server == null || server.substringBefore('?') != readHostServerUrl) {
            _uiState.update { it.copy(error = "Wait for this chat’s server connection before sending") }
            return
        }
        val pending = ChatSubmission(UUID.randomUUID().toString(), server.substringBefore('?'), binding.conversationKey,
            binding.id, state.sessionName, state.windowIndex, text, draftKey(state.sessionName, state.windowIndex), state.attachedFile, widgetId)
        pendingSend = pending
        restoredSubmissionDrafts += pending.id
        _uiState.update { it.copy(isSending = true, submissionNotice = pending.notice, error = null) }
        viewModelScope.launch {
            try {
                if (!submissions.begin(pending)) {
                    _uiState.update { it.copy(isSending = false, submissionNotice = null, error = "A previous message is still being checked") }
                    return@launch
                }
                val payload = buildMap<String, Any?> {
                    put("type", if (widgetId != null) "send-bound-ui-action" else if (pending.file == null) "send-bound-chat-message" else "send-bound-file-to-chat")
                    if (widgetId != null) put("widgetId", widgetId)
                    put("bindingId", binding.id); put("requestId", pending.id)
                    put("sessionName", state.sessionName); put("windowIndex", state.windowIndex)
                    if (pending.file == null) put("message", text.trim()) else {
                        val data = withContext(Dispatchers.IO) { encodeChatAttachment(app.contentResolver.openInputStream(android.net.Uri.parse(pending.file.uri))) }
                        put("file", mapOf("filename" to pending.file.filename, "mimeType" to pending.file.mimeType, "data" to data))
                        put("prompt", text.trim().ifBlank { null })
                    }
                }
                webSocketService.checkConnection()
                if (!webSocketService.sendIfConnected(payload, server)) throw java.io.IOException("Unable to send. Your draft is still here; check the connection")
                recoverSubmissions()
                delay(30_000)
                if (submissions.records.first().any { it.id == pending.id && it.waiting }) {
                    submissions.checking(pending.id, sendReceiptsAvailable)
                    if (screenActive) { webSocketService.checkConnection(); recoverSubmissions() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                submissions.record(pending.serverUrl, mapOf("requestId" to pending.id, "bindingId" to pending.bindingId,
                    "status" to "complete", "success" to false, "error" to (error.message ?: "Unable to send this draft")))
            }
        }
    }

    private suspend fun handleSendResult(message: Map<String, Any?>) {
        val server = message["_sourceServerUrl"] as? String ?: readHostServerUrl ?: return
        submissions.record(server, message)
    }

    private suspend fun handleBinding(message: Map<String, Any?>) {
        val current = _uiState.value
        if (message["sessionName"] != current.sessionName || (message["windowIndex"] as? Number)?.toInt() != current.windowIndex || current.isAcp) return
        val next = ChatBindingState.parse(message) ?: return
        sendReceiptsAvailable = message["sendReceipts"] == true
        val oldKey = current.bindingState?.binding?.conversationKey ?: cachedConversationKey
        val newKey = next.binding?.conversationKey
        val changed = newKey != null && oldKey != newKey
        if (changed) {
            persistChat(immediate = true)
            resetHistoryPaging()
        }
        if (newKey != null) cachedConversationKey = newKey
        if (changed && oldKey != null) saveDraft(draftKey(current.sessionName, current.windowIndex), current.draftMessage)
        _uiState.update { it.copy(bindingState = next, detectedTool = next.tool.ifBlank { it.detectedTool },
            isLoading = false, isLoadingMore = false, error = null,
            pendingPermission = if (changed) null else it.pendingPermission,
            firstUnreadMessageId = if (changed) null else it.firstUnreadMessageId) }
        if (changed) {
            _uiState.value.messages.clear()
            visitUnreadCursor = null
            val key = draftKey(current.sessionName, current.windowIndex)
            val saved = dataStore.getDraftMessage(key)
            val draft = saved ?: if (oldKey == null) _uiState.value.draftMessage else ""
            _uiState.update { it.copy(draftMessage = draft, attachedFile = if (oldKey == null) it.attachedFile else null) }
            if (saved == null && draft.isNotBlank()) dataStore.setDraftMessage(key, draft)
        }
        readTarget?.let { chatActivityRepository.linkConversation(it, next) }
        recoverSubmissions()
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMoreMessages || state.isLoading) return
        val target = readTarget ?: return
        val generation = cacheLoadGeneration
        val expectedPagingGeneration = pagingGeneration
        _uiState.update { it.copy(isLoadingMore = true) }
        historyPageJob = viewModelScope.launch {
            try {
                // During a reconnect gap, fetch the missing remote middle before older local pages.
                if (!preferRemoteChatHistory(webSocketService.isConnected, cachedHistoryComplete, remoteHasMoreMessages, remoteGapBoundaryIds.isNotEmpty())) {
                    val page = offlineCache.historyPage(target, oldestCachedPosition, beforeMessageId = state.messages.firstOrNull()?.id)
                    if (generation != cacheLoadGeneration || expectedPagingGeneration != pagingGeneration) return@launch
                    val sameConversation = cachedConversationKey == null || page?.conversation?.conversationKey == cachedConversationKey
                    if (page != null && sameConversation) {
                        oldestCachedPosition = page.oldestPosition ?: oldestCachedPosition
                        hasOlderCachedMessages = page.hasMore
                        cachedHistoryComplete = page.conversation.complete
                        val before = _uiState.value.messages.toList()
                        val ids = before.mapTo(hashSetOf()) { it.id }
                        val older = page.messages.filter { it.id !in ids }
                        if (older.isNotEmpty()) {
                            prependMessages(older)
                            if (cachedHistoryComplete && remoteGapBoundaryIds.isEmpty()) remoteLoadedCount = maxOf(remoteLoadedCount, _uiState.value.messages.size)
                            if (cachedHistoryComplete && page.totalMessageCount >= _uiState.value.totalMessageCount) remoteHasMoreMessages = false
                            _uiState.update { it.copy(isLoadingMore = false, hasMoreMessages = canLoadOlderMessages()) }
                            chatActivityRepository.olderHistory(target, older)
                            updateUnreadBoundary()
                            return@launch
                        }
                        if (cachedHistoryComplete && page.totalMessageCount >= _uiState.value.totalMessageCount) {
                            remoteHasMoreMessages = false
                            remoteGapBoundaryIds = emptySet()
                        }
                    }
                }
                if (!webSocketService.isConnected || !remoteHasMoreMessages) {
                    _uiState.update { it.copy(isLoadingMore = false, hasMoreMessages = canLoadOlderMessages()) }
                    return@launch
                }
                val current = _uiState.value
                requestedRemotePage = true
                webSocketService.loadMoreChatHistory(
                    sessionName = current.sessionName,
                    windowIndex = current.windowIndex,
                    offset = remoteLoadedCount,
                    limit = 100,
                    bindingId = current.bindingState?.binding?.id,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("ChatViewModel", "Could not load older saved history", error)
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
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
            webSocketService.clearChatLog(state.sessionName, state.windowIndex, state.bindingState?.binding?.id)
        }
    }

    // -------------------------------------------------------------------------
    // Draft persistence
    // -------------------------------------------------------------------------

    fun updateDraft(text: String) {
        val state = _uiState.value
        _uiState.update { it.copy(draftMessage = text) }
        if (navShareId.isNotBlank()) savedStateHandle["share_draft_text"] = text
        saveDraft(draftKey(state.sessionName, state.windowIndex), text)
        markSubmissionDraftEdited(text, state.attachedFile)
    }

    // -------------------------------------------------------------------------
    // Voice recording
    // -------------------------------------------------------------------------

    fun startVoiceRecording() {
        if (_uiState.value.bindingState?.canSend == false) { _uiState.update { it.copy(error = "Waiting for this terminal’s conversation before recording") }; return }
        viewModelScope.launch { audioService.startRecording() }
    }

    fun stopVoiceRecording() {
        viewModelScope.launch {
            val path = audioService.stopRecording() ?: return@launch
            _uiState.update { it.copy(isTranscribing = true, transcriptionError = null) }
            val apiKey = dataStore.openaiApiKey.first()
            val state = _uiState.value
            transcriptionQueue.enqueue(path, apiKey, com.agentshell.data.model.TranscriptionJob.Source.CHAT, draftKey(state.sessionName, state.windowIndex))
        }
    }

    private fun receiveTranscription(jobId: String, text: String, context: String?) {
        if (!transcriptionReceipt.accept(jobId)) return
        val current = _uiState.value
        if (context != null && context != draftKey(current.sessionName, current.windowIndex)) {
            viewModelScope.launch {
                val original = dataStore.getDraftMessage(context).orEmpty()
                dataStore.setDraftMessage(context, mergeSharedText(original, text))
            }
            return // A late recording stays with the conversation it was recorded for.
        }
        _uiState.update { it.copy(isTranscribing = false, transcribedText = text, transcriptionError = null, failedTranscriptionJobId = null) }
    }

    fun cancelVoiceRecording() {
        viewModelScope.launch { audioService.cancelRecording() }
    }

    fun consumeTranscribedText() {
        val state = _uiState.value
        val text = state.transcribedText ?: return
        val draft = if (state.draftMessage.isBlank()) text else "${state.draftMessage} $text"
        _uiState.update { it.copy(transcribedText = null) }
        if (text.isBlank()) return
        updateDraft(draft)
        viewModelScope.submitVoiceDraft(draft, { isVoiceAutoEnter() }, { _uiState.value.draftMessage }, ::sendMessage)
    }

    fun retryTranscription(jobId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTranscribing = true, transcriptionError = null) }
            val apiKey = dataStore.openaiApiKey.first()
            transcriptionQueue.retryJob(jobId, apiKey)
        }
    }

    fun dismissTranscriptionError() {
        _uiState.update { it.copy(transcriptionError = null, failedTranscriptionJobId = null) }
    }

    suspend fun isVoiceAutoEnter(): Boolean = dataStore.voiceAutoEnter.first()

    suspend fun isVoiceButtonVisible(): Boolean = dataStore.showVoiceButton.first()

    val recentChatSessions = recentSessions.chat
    fun canSwipeToSession(key: String) = key in recentSessions.chat.value
    suspend fun pushRecentChatSession(key: String) = recentSessions.visitChat(key)

    // -------------------------------------------------------------------------
    // File attachment
    // -------------------------------------------------------------------------

    fun attachFile(uri: String, filename: String, mimeType: String, size: Long) {
        val file = AttachedFile(uri, filename, mimeType, size)
        _uiState.update { it.copy(attachedFile = file) }
        savedStateHandle["share_attachment"] = Json.encodeToString(file)
        markSubmissionDraftEdited(_uiState.value.draftMessage, file)
    }

    fun removeAttachedFile() {
        _uiState.update { it.copy(attachedFile = null) }
        clearSharedAttachment()
        markSubmissionDraftEdited(_uiState.value.draftMessage, null)
    }

    private fun markSubmissionDraftEdited(text: String, file: AttachedFile?) {
        val ticket = (pendingSend ?: activeSubmission)?.takeIf {
            it.serverUrl == readHostServerUrl && it.bindingId == _uiState.value.bindingState?.binding?.id
        } ?: return
        viewModelScope.launch { submissions.draftEdited(ticket.id, text, file) }
    }

    private fun clearSharedAttachment() {
        savedStateHandle.remove<String>("share_attachment")
        if (navShareId.isNotBlank()) viewModelScope.launch { sharedContentRepository.discard(navShareId) }
    }

    private fun draftKey(sessionName: String, windowIndex: Int): String = _uiState.value.bindingState?.binding?.conversationKey?.let {
        "chat_draft_native_${readHostServerUrl.orEmpty()}:$it"
    } ?: "chat_draft_${sessionName}_$windowIndex"

    private fun saveDraft(key: String, text: String) {
        val keepEmpty = _uiState.value.submissionNotice != null || _uiState.value.isSending
        viewModelScope.launch {
            dataStore.setDraftMessage(key, text, keepEmpty)
        }
    }

    private fun restoreDraft(key: String) {
        viewModelScope.launch {
            val draft = dataStore.getDraftMessage(key).orEmpty()
            if (navShareId.isBlank() || savedStateHandle.get<Boolean>("share_applied") == true) {
                _uiState.update { it.copy(draftMessage = savedStateHandle.get<String>("share_draft_text") ?: draft) }
                return@launch
            }
            try {
                val shared = sharedContentRepository.load(navShareId)
                    ?: throw java.io.IOException("Shared content is no longer available. Please share it again")
                val text = mergeSharedText(draft, shared.text)
                val file = shared.attachment?.let { AttachedFile(it.uri, it.filename, it.mimeType, it.sizeBytes) }
                _uiState.update { it.copy(draftMessage = text, attachedFile = file) }
                savedStateHandle["share_applied"] = true
                savedStateHandle["share_draft_text"] = text
                file?.let { savedStateHandle["share_attachment"] = Json.encodeToString(it) }
                dataStore.setDraftMessage(key, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(draftMessage = draft, error = error.message ?: "Unable to restore shared content") }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Message collection loop
    // -------------------------------------------------------------------------

    private fun collectMessages() {
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            chatRepository.chatMessages.collect { message ->
                if (readHostServerUrl != null && message["_sourceServerUrl"] != null && message["_sourceServerUrl"] != readHostServerUrl) return@collect
                if (!chatRepository.isChatMessage(message)) return@collect
                // Delivery acknowledgements are independent of a history/cache refresh.
                if (message["type"] == "chat-send-result") { handleSendResult(message); return@collect }
                if (restoringCache) return@collect
                if (message["type"] == "chat-binding") { handleBinding(message); return@collect }
                if (!_uiState.value.bindingState.acceptsConversationPacket(message)) return@collect
                val beforeMessages = _uiState.value.messages.toList()
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
                if (beforeMessages !== _uiState.value.messages.toList() && message["type"] !in setOf("chat-history", "chat-history-chunk", "acp-history-loaded", "chat-log-cleared")) persistChat()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Message handlers
    // -------------------------------------------------------------------------

    private suspend fun handleChatHistory(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val generation = cacheLoadGeneration
        val expectedConversation = cachedConversationKey
        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val parsed = withContext(Dispatchers.Default) {
            messagesData.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
            }
        }
        if (generation != cacheLoadGeneration || expectedConversation != cachedConversationKey) return
        val totalCount = (message["totalCount"] as? Number)?.toInt() ?: parsed.size
        val hasMore = message["hasMore"] as? Boolean ?: false

        val tool = (message["tool"] as? String)?.lowercase() ?: state.bindingState?.tool
        if (message["bindingId"] == null && state.bindingState?.status == "checking") _uiState.update { it.copy(bindingState = null) }
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
        applyRemoteHistory(parsed, hasMore)
        saveRemoteHistory(parsed, !hasMore, tool)
        reconcileReadHistory(parsed)

        when (tool) {
            "claude" -> systemRepository.requestClaudeUsage()
            "codex" -> systemRepository.requestCodexUsage()
        }
    }

    private suspend fun handleChatHistoryChunk(message: Map<String, Any?>) {
        if (!requestedRemotePage) return
        flushPendingAcpChunk()
        val sessionName = (message["sessionName"] ?: message["session-name"]) as? String ?: return
        val windowIndex = (message["windowIndex"] ?: message["window-index"])?.let {
            (it as? Number)?.toInt()
        } ?: return

        val generation = cacheLoadGeneration
        val expectedPagingGeneration = pagingGeneration
        val expectedConversation = cachedConversationKey
        val state = _uiState.value
        if (sessionName != state.sessionName || windowIndex != state.windowIndex) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val newMessages = withContext(Dispatchers.Default) {
            messagesData.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
            }
        }
        if (generation != cacheLoadGeneration || expectedConversation != cachedConversationKey || expectedPagingGeneration != pagingGeneration) return
        val hasMore = message["hasMore"] as? Boolean ?: false

        requestedRemotePage = false
        val previousRemoteOffset = remoteLoadedCount
        remoteHasMoreMessages = hasMore
        if (newMessages.any { it.id in remoteGapBoundaryIds }) remoteGapBoundaryIds = emptySet()
        val merged = com.agentshell.data.repository.mergeCachedMessages(state.messages.toList(), newMessages)
        replaceMessages(merged)
        remoteLoadedCount = advanceRemoteChatHistoryOffset(previousRemoteOffset, newMessages.size, merged.size, cachedHistoryComplete && remoteGapBoundaryIds.isEmpty())
        readTarget?.let { offlineCache.saveHistoryAsync(it, newMessages, false, cachedConversationKey, state.detectedTool) }
        _uiState.update { it.copy(readTrackingReady = false) }
        viewModelScope.launch {
            readTarget?.let { target ->
                chatActivityRepository.olderHistory(target, newMessages)
                readState = chatActivityRepository.states.value[target.key]
            }
            _uiState.update { it.copy(isLoadingMore = false, hasMoreMessages = canLoadOlderMessages()) }
            updateUnreadBoundary()
            _uiState.update { it.copy(readTrackingReady = true) }
        }
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
        val existingIndex = messages.indexOfFirst { it.id == msg.id }
        if (existingIndex >= 0) messages[existingIndex] = msg else messages.add(msg)
        updateUnreadBoundary()
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
            cacheSaveJob?.cancel()
            cacheSaveJob = null
            cacheDirty = false
            resetHistoryPaging()
            visitUnreadCursor = null
            readTarget?.let { target -> viewModelScope.launch { chatActivityRepository.clear(target) } }
            _uiState.update {
                ChatUiState(
                    sessionName = it.sessionName,
                    windowIndex = it.windowIndex,
                    isAcp = it.isAcp,
                    showThinking = it.showThinking,
                    showToolCalls = it.showToolCalls,
                    fileBaseUrl = it.fileBaseUrl,
                    sessionCwd = it.sessionCwd,
                    readTrackingReady = true,
                )
            }
            readTarget?.let { offlineCache.saveHistoryAsync(it, emptyList(), true, cachedConversationKey) }
            _uiState.update { it.copy(isLoading = false) }
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
        updateUnreadBoundary()
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

    private suspend fun handleAcpHistoryLoaded(message: Map<String, Any?>) {
        flushPendingAcpChunk()
        val sessionId = message["sessionId"] as? String ?: return
        val generation = cacheLoadGeneration
        val expectedConversation = cachedConversationKey
        val state = _uiState.value
        if (!matchesAcpSession(sessionId, state.sessionName)) return

        val messagesData = message["messages"] as? List<*> ?: emptyList<Any>()
        val parsed = withContext(Dispatchers.Default) {
            messagesData.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                (it as? Map<String, Any?>)?.let { m -> parseMessage(m) }
            }
        }
        if (generation != cacheLoadGeneration || expectedConversation != cachedConversationKey) return
        val hasMore = message["hasMore"] as? Boolean ?: false

        applyRemoteHistory(parsed, hasMore)
        _uiState.update { it.copy(isLoading = false, hasMoreMessages = canLoadOlderMessages()) }
        saveRemoteHistory(parsed, !hasMore)
        reconcileReadHistory(parsed)
    }

    private fun applyRemoteHistory(parsed: List<ChatMessage>, hasMore: Boolean) {
        historyPageJob?.cancel()
        historyPageJob = null
        pagingGeneration += 1
        requestedRemotePage = false
        val previous = _uiState.value.messages.toList()
        val incomingIds = parsed.mapTo(hashSetOf()) { it.id }
        val overlaps = previous.any { it.id in incomingIds }
        remoteGapBoundaryIds = if (hasMore && previous.isNotEmpty() && !overlaps) previous.mapTo(hashSetOf()) { it.id } else emptySet()
        remoteHasMoreMessages = hasMore
        if (!hasMore) {
            hasOlderCachedMessages = false
            oldestCachedPosition = null
            cachedHistoryComplete = true
        }
        val merged = if (hasMore) com.agentshell.data.repository.mergeCachedMessages(previous, parsed) else parsed
        remoteLoadedCount = remoteChatHistoryOffset(parsed.size, merged.size, cachedHistoryComplete, overlaps)
        replaceMessages(merged)
        _uiState.update { it.copy(isLoadingMore = false, hasMoreMessages = canLoadOlderMessages()) }
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private fun parseMessage(data: Map<String, Any?>): ChatMessage = ChatMessageParser.parse(data)

    private fun buildUserMessage(text: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        type = "user",
        content = text,
        timestamp = System.currentTimeMillis(),
        blocks = listOf(ChatBlock(type = "text", text = text)),
    )

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
        flushPendingAcpChunk()
        persistChat(immediate = true)
        chatActivityRepository.setChatOpen(readOwner, null, false)
        chatActivityRepository.setViewing(readOwner, null, false)
        clearPendingAcpChunk()
        super.onCleared()
        messageCollectionJob?.cancel()
    }
}
