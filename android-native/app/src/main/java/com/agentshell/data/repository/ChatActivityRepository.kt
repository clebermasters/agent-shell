package com.agentshell.data.repository

import android.content.Context
import android.os.SystemClock
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageParser
import com.agentshell.data.model.ChatReadState
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus
import com.agentshell.data.model.AgentSignalLedger
import com.agentshell.data.model.AgentSignalKind
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.Host
import com.agentshell.data.remote.SessionSocket
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Application-owned chat watching and durable read positions, independent of which screen is open. */
@Singleton
class ChatActivityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesDataStore,
    private val hosts: HostRepository,
    private val webSocket: WebSocketService,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _states = MutableStateFlow<Map<String, ChatReadState>>(emptyMap())
    val states: StateFlow<Map<String, ChatReadState>> = _states.asStateFlow()
    private val _agentActivities = MutableStateFlow<Map<String, AgentActivity>>(emptyMap())
    val agentActivities: StateFlow<Map<String, AgentActivity>> = _agentActivities.asStateFlow()
    private val viewers = mutableMapOf<String, String>() // owner -> chat key
    private val monitors = mutableMapOf<String, Pair<SessionSocket, CoroutineScope>>()
    private val lastAlert = mutableMapOf<String, Long>()
    private var selectedHost: Host? = null
    private var availableSessions: Set<String>? = null
    private var notificationsEnabled = true
    private var finishedEnabled = true
    private var quietEnabled = true
    private val signalMutex = Mutex()
    private var signalLedgers: Map<String, AgentSignalLedger> = emptyMap()
    private val listViewers = mutableSetOf<String>()
    private val knownWindows = mutableMapOf<String, Set<Int>>()
    private var started = false

    private data class StreamReply(
        val target: ChatTarget,
        val turnId: String,
        val preview: StringBuilder = StringBuilder(),
        var alerted: Boolean = false,
        var alertJob: Job? = null,
        var sequence: Int = 0,
    )
    private val streams = mutableMapOf<String, StreamReply>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            _states.value = runCatching { json.decodeFromString<Map<String, ChatReadState>>(preferences.chatReadStates.first()) }
                .getOrDefault(emptyMap())
            notificationsEnabled = preferences.chatNotificationsEnabled.first()
            finishedEnabled = preferences.agentFinishedEnabled.first()
            quietEnabled = preferences.agentQuietEnabled.first()
            signalLedgers = runCatching { json.decodeFromString<Map<String, AgentSignalLedger>>(preferences.agentSignalLedgers.first()) }.getOrDefault(emptyMap())
            ready.complete(Unit)
            launch {
                preferences.chatNotificationsEnabled.collect { enabled ->
                    notificationsEnabled = enabled
                    if (!enabled) _states.value.values.forEach { NotificationHelper.cancelChat(context, it.target) }
                    syncMonitors()
                }
            }
            launch {
                hosts.getSelectedHost().collect { host ->
                    if (host?.id != selectedHost?.id) {
                        availableSessions = null
                        knownWindows.clear()
                        streams.values.forEach { it.alertJob?.cancel() }
                        streams.clear()
                    }
                    selectedHost = host
                    syncMonitors()
                }
            }
            launch {
                webSocket.connectionStatus.collect { status ->
                    if (status == ConnectionStatus.CONNECTED) {
                        webSocket.send(mapOf("type" to "get-agent-activities"))
                        syncMonitors()
                    }
                    else selectedHost?.id?.let { invalidateHostActivity(it) }
                }
            }
            launch { webSocket.keepAliveEnabled.collect { syncMonitors() } }
            launch { preferences.agentFinishedEnabled.collect { finishedEnabled = it; syncMonitors() } }
            launch { preferences.agentQuietEnabled.collect { quietEnabled = it; syncMonitors() } }
            webSocket.messages.collect { message ->
                val host = selectedHost ?: return@collect
                // Never attribute events from a previous server to the newly selected host.
                if (webSocket.currentWebSocketUrl?.substringBefore('?') != "${host.wsUrl}/ws") return@collect
                if (message["_sourceServerUrl"] != null && message["_sourceServerUrl"] != "${host.wsUrl}/ws") return@collect
                handleEvent(host.id, message)
            }
        }
    }

    suspend fun register(target: ChatTarget): ChatReadState {
        start()
        ready.await()
        val state = update(target) { current ->
            current.copy(target = target.copy(cwd = target.cwd.ifBlank { current.target.cwd }, title = target.title.ifBlank { current.target.title }))
        }
        syncMonitors()
        return state
    }

    fun setViewing(owner: String, target: ChatTarget?, viewing: Boolean) {
        if (viewing && target != null) viewers[owner] = target.key else viewers.remove(owner)
        if (viewing && target != null) NotificationHelper.cancelAgentSignal(context, target)
    }

    fun setListViewing(owner: String, viewing: Boolean) {
        if (viewing) listViewers.add(owner) else listViewers.remove(owner)
        syncMonitors()
    }

    private fun isViewing(target: ChatTarget) = target.key in viewers.values

    suspend fun history(target: ChatTarget, messages: List<ChatMessage>) {
        ready.await()
        var replies = emptyList<ChatMessage>()
        val state = update(target) { current ->
            val result = current.reconcileHistory(messages)
            replies = result.second
            result.first
        }
        replies.lastOrNull()?.let { notifyReply(state, it.content ?: "New reply") }
    }

    suspend fun readThrough(target: ChatTarget, visiblePrefix: List<ChatMessage>) {
        ready.await()
        val state = update(target) { it.readThrough(visiblePrefix) }
        if (state.unread.isEmpty()) NotificationHelper.cancelChat(context, target)
    }

    suspend fun olderHistory(target: ChatTarget, messages: List<ChatMessage>) {
        ready.await()
        update(target) { it.includeOlderUnread(messages) }
    }

    suspend fun clear(target: ChatTarget) {
        ready.await()
        update(target) { ChatReadState(target, initialized = true) }
        NotificationHelper.cancelChat(context, target)
    }

    private suspend fun update(target: ChatTarget, transform: (ChatReadState) -> ChatReadState): ChatReadState = mutex.withLock {
        val current = _states.value[target.key] ?: ChatReadState(target)
        val next = transform(current)
        if (_states.value[target.key] != next) {
            _states.value = _states.value + (target.key to next)
            preferences.setChatReadStates(json.encodeToString(_states.value))
        }
        next
    }

    private fun notifyReply(state: ChatReadState, preview: String, silent: Boolean = false): Boolean {
        if (!notificationsEnabled || isViewing(state.target) || state.unread.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        val coalesce = lastAlert[state.target.key]?.let { now - it < 5_000 } == true
        val requestSound = !silent && !coalesce
        NotificationHelper.showChat(context, state.target, preview.trim().take(300).ifBlank { "New reply" }, state.unread.size, silent = !requestSound)
        if (requestSound) lastAlert[state.target.key] = now
        // A silent preview must not prevent an audible alert when the reply completes later.
        return requestSound
    }

    @Suppress("UNCHECKED_CAST")
    internal suspend fun handleEvent(hostId: String, message: Map<String, Any?>, watchedTarget: ChatTarget? = null) {
        handleAgentActivity(hostId, message, watchedTarget)
        when (message["type"] as? String) {
            "sessions-list", "session_list" -> {
                if (watchedTarget != null) return
                val sessions = message["sessions"] as? List<Map<String, Any?>> ?: return
                availableSessions = sessions.mapNotNull { it["name"] as? String }.toSet()
                for (session in sessions) {
                    val name = session["name"] as? String ?: continue
                    if ((session["tool"] as? String).isNullOrBlank() && !quietEnabled && listViewers.isEmpty()) continue
                    for (index in knownWindows[name] ?: setOf(0)) register(ChatTarget.create(hostId, name, index))
                    val existing = monitors.entries.firstOrNull { _states.value[it.key]?.target?.sessionName == name }
                    existing?.value?.first?.send(mapOf("type" to "list-windows", "sessionName" to name))
                }
                syncMonitors()
            }
            "windows-list" -> {
                val name = message["sessionName"] as? String ?: return
                if (watchedTarget == null || watchedTarget.sessionName != name || watchedTarget.hostId != hostId) return
                val indexes = (message["windows"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { (it["index"] as? Number)?.toInt() }.toSet()
                if (indexes.isEmpty() || knownWindows[name] == indexes) return
                scope.launch {
                    if (selectedHost?.id != hostId) return@launch
                    knownWindows[name] = indexes
                    for (index in indexes) update(ChatTarget.create(hostId, name, index)) { it }
                    syncMonitors()
                }
            }
            "chat-history", "acp-history-loaded" -> {
                val target = eventTarget(hostId, message) ?: watchedTarget ?: return
                val messages = (message["messages"] as? List<Map<String, Any?>>)?.map(ChatMessageParser::parse) ?: emptyList()
                history(target, messages)
            }
            "acp-sessions-listed" -> {
                if (watchedTarget != null) return
                val sessions = message["sessions"] as? List<Map<String, Any?>> ?: return
                for (session in sessions) {
                    val id = session["sessionId"] as? String ?: continue
                    register(ChatTarget.create(hostId, id, isAcp = true, cwd = session["cwd"] as? String ?: "", title = session["title"] as? String ?: ""))
                }
            }
            "chat-event" -> {
                val target = eventTarget(hostId, message) ?: return
                val raw = message["message"] as? Map<String, Any?> ?: return
                val parsed = ChatMessageParser.parse(raw)
                var incoming = false
                val state = update(target) {
                    val result = it.receive(parsed)
                    incoming = result.second
                    result.first
                }
                if (incoming) notifyReply(state, parsed.content ?: "New attachment")
            }
            "acp-message-chunk" -> {
                if (watchedTarget != null || message["isThinking"] == true) return
                val target = eventTarget(hostId, message) ?: return
                val content = message["content"] as? String ?: return
                if (content.isBlank()) return
                val stream = streams.getOrPut(target.key) { StreamReply(target, "stream:${target.key}:${System.currentTimeMillis()}") }
                if (stream.preview.length < 300) stream.preview.append(content.take(300 - stream.preview.length))
                if (!isViewing(target) && _states.value[target.key]?.unread?.none { it.id.startsWith(stream.turnId) } != false) {
                    val reply = ChatMessage("${stream.turnId}:${stream.sequence++}", "assistant", stream.preview.toString(), System.currentTimeMillis())
                    update(target) { it.receive(reply).first.copy(latestReceived = it.latestReceived) }
                }
                if (!stream.alerted && stream.alertJob == null) {
                    stream.alertJob = scope.launch {
                        delay(800)
                        val state = _states.value[target.key] ?: return@launch
                        stream.alerted = notifyReply(state, stream.preview.toString())
                        stream.alertJob = null
                    }
                }
            }
            "acp-prompt-done" -> {
                if (watchedTarget != null) return
                val target = eventTarget(hostId, message) ?: return
                val stream = streams.remove(target.key) ?: return
                stream.alertJob?.cancel()
                _states.value[target.key]?.let { notifyReply(it, stream.preview.toString(), silent = stream.alerted) }
            }
            "chat-log-cleared" -> {
                if (message["success"] == true) eventTarget(hostId, message)?.let { clear(it) }
            }
        }
    }

    internal fun handleAgentActivity(hostId: String, message: Map<String, Any?>, watchedTarget: ChatTarget? = null) {
        val target = eventTarget(hostId, message) ?: return
        if (watchedTarget != null && target.key != watchedTarget.key) return
        val current = _agentActivities.value[target.key] ?: AgentActivity()
        val now = SystemClock.elapsedRealtime()
        val next = if (message["type"] == "chat-activity" || (message["type"] == "acp-prompt-done" && message["activity"] is Map<*, *>)) {
            val input = if (message["type"] == "chat-activity") message else message + ("state" to message["activity"])
            val parsed = AgentActivity.parse(input, now) ?: return
            if (!current.accepts(parsed)) {
                if (message["type"] == "acp-prompt-done") processAgentSignal(target, parsed)
                return
            }
            parsed
        } else {
            // Older servers can still report direct-agent work through existing protocol events.
            if (!target.isAcp || (message["type"] != "acp-prompt-done" && current.backendSnapshot && !current.expired(now))) return
            val status = when (message["type"]) {
                "acp-message-chunk", "acp-tool-call" -> AgentActivityStatus.WORKING
                "acp-permission-request" -> AgentActivityStatus.WAITING
                "acp-prompt-done" -> if (message["stopReason"] == "failed") AgentActivityStatus.FAILED else AgentActivityStatus.IDLE
                "acp-error" -> AgentActivityStatus.FAILED
                else -> return
            }
            AgentActivity(
                status = status, detail = when (status) {
                    AgentActivityStatus.WORKING -> "Agent is working"
                    AgentActivityStatus.WAITING -> "Waiting for your approval"
                    AgentActivityStatus.IDLE -> "Agent turn ended"
                    AgentActivityStatus.FAILED -> "Agent encountered an error"
                    else -> "Agent status is not available"
                }, source = "agent-protocol", confidence = "reported",
                startedAt = if (status == AgentActivityStatus.WORKING) current.startedAt ?: System.currentTimeMillis() else null,
                observedAt = System.currentTimeMillis(), receivedAt = now,
                turnId = current.turnId,
                finishedAt = if (message["type"] == "acp-prompt-done") System.currentTimeMillis() else null,
                completionReason = if (message["type"] == "acp-prompt-done") when (message["stopReason"]) { "failed" -> "failed"; "cancelled", "interrupted" -> "interrupted"; else -> "completed" } else null,
            )
        }
        _agentActivities.value = _agentActivities.value + (target.key to next)
        processAgentSignal(target, next)
    }

    private fun processAgentSignal(target: ChatTarget, next: AgentActivity) {
        scope.launch {
            ready.await()
            signalMutex.withLock {
                val ledger = signalLedgers[target.key] ?: AgentSignalLedger()
                val (updated, signal) = ledger.observe(next)
                if (updated != ledger) {
                    signalLedgers = (signalLedgers - target.key).entries.toList().takeLast(255).associate { it.toPair() } + (target.key to updated)
                    preferences.setAgentSignalLedgers(json.encodeToString(signalLedgers))
                }
                if (signal != null && !isViewing(target) && ((signal.kind == AgentSignalKind.FINISHED && finishedEnabled) || (signal.kind == AgentSignalKind.QUIET && quietEnabled))) {
                    NotificationHelper.showAgentSignal(context, target, signal)
                }
            }
        }
    }

    internal suspend fun awaitAgentSignals() { ready.await(); signalMutex.withLock { } }

    internal fun invalidateActivity(target: ChatTarget) {
        val current = _agentActivities.value[target.key] ?: return
        _agentActivities.value = _agentActivities.value + (target.key to current.unknown("Connection unavailable"))
    }

    private fun invalidateHostActivity(hostId: String) {
        _states.value.values.filter { it.target.hostId == hostId }.forEach { invalidateActivity(it.target) }
    }

    private fun eventTarget(hostId: String, message: Map<String, Any?>): ChatTarget? {
        val session = (message["sessionName"] ?: message["session-name"] ?: message["sessionId"]) as? String ?: return null
        val isAcp = message.containsKey("sessionId") || session.startsWith("acp_")
        val index = ((message["windowIndex"] ?: message["window-index"]) as? Number)?.toInt() ?: 0
        val target = ChatTarget.create(hostId, session, index, isAcp)
        return _states.value[target.key]?.target ?: target
    }

    private fun syncMonitors() {
        val host = selectedHost
        val url = webSocket.currentWebSocketUrl
        val canWatch = (notificationsEnabled || finishedEnabled || quietEnabled || listViewers.isNotEmpty()) && webSocket.keepAliveEnabled.value && host != null && url?.substringBefore('?') == "${host.wsUrl}/ws"
        val desired = if (canWatch) _states.value.values.map { it.target }.filter {
            it.hostId == host!!.id && !it.isAcp && (availableSessions == null || it.sessionName in availableSessions!!)
                && (knownWindows[it.sessionName]?.contains(it.windowIndex) != false)
        }.associateBy { it.key } else emptyMap()
        for (key in monitors.keys.toList()) {
            if (key !in desired) monitors.remove(key)?.let { (socket, monitorScope) -> socket.dispose(); monitorScope.cancel() }
        }
        for ((key, target) in desired) {
            if (key in monitors) continue
            val socket = SessionSocket(client)
            val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            monitors[key] = socket to monitorScope
            monitorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                socket.messages.collect { handleEvent(target.hostId, it, target) }
            }
            monitorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                socket.isConnected.collect { connected ->
                    if (connected) {
                        socket.send(mapOf("type" to "list-windows", "sessionName" to target.sessionName))
                        socket.send(mapOf("type" to "watch-chat-log", "sessionName" to target.sessionName, "windowIndex" to target.windowIndex, "limit" to 50))
                    }
                    else invalidateActivity(target)
                }
            }
            socket.connect(url!!)
        }
    }

    internal fun close() {
        monitors.values.forEach { (socket, monitorScope) -> socket.dispose(); monitorScope.cancel() }
        monitors.clear()
        streams.values.forEach { it.alertJob?.cancel() }
        scope.cancel()
    }
}
