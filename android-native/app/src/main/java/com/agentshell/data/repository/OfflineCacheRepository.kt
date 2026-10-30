package com.agentshell.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.CachedChatMessage
import com.agentshell.data.local.CachedHistorySync
import com.agentshell.data.local.CachedConversation
import com.agentshell.data.local.CachedSession
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageParser
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.Host
import com.agentshell.data.model.TmuxSession
import com.agentshell.data.remote.SessionSocket
import com.agentshell.data.remote.WebSocketService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

data class CachedSessionList(
    val hostId: String? = null,
    val tmux: List<TmuxSession> = emptyList(),
    val acp: List<AcpSession> = emptyList(),
    val windows: Map<String, List<Int>> = emptyMap(),
)

data class CachedHistory(val conversation: CachedConversation, val messages: List<ChatMessage>)

data class CachedHistoryPage(
    val conversation: CachedConversation,
    val messages: List<ChatMessage>,
    val oldestPosition: Int?,
    val totalMessageCount: Int,
    val hasMore: Boolean,
)

/** Durable, host-scoped storage. There is deliberately no TTL, size cap, or eviction. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OfflineCacheRepository @Inject constructor(
    private val database: AppDatabase,
    private val hosts: HostRepository,
    private val webSocket: WebSocketService,
    private val client: OkHttpClient,
    private val attachments: OfflineAttachmentStore,
) {
    private val dao = database.offlineCacheDao()
    private val json = Json { ignoreUnknownKeys = true }
    // Cache serialization/backfill must not occupy the whole shared IO pool while
    // Compose is trying to draw a navigation transition.
    private val cacheDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + cacheDispatcher)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private data class HistoryWrite(val target: ChatTarget, val messages: List<ChatMessage>, val complete: Boolean, val conversationKey: String?, val tool: String?)
    private data class PendingHistoryWrite(var authoritative: HistoryWrite? = null, var latest: HistoryWrite? = null)
    private val pendingWriteLock = Any()
    private val pendingWrites = linkedMapOf<String, PendingHistoryWrite>()
    private val historyRevisions = mutableMapOf<String, Long>()
    private val knownConversationKeys = mutableMapOf<String, String>()
    private val historyWriteSignals = Channel<Unit>(Channel.CONFLATED)

    val sessions: StateFlow<CachedSessionList> = hosts.getSelectedHost()
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { host ->
            if (host == null) flowOf(CachedSessionList())
            else dao.observeSessions(host.id).map { rows ->
                CachedSessionList(
                    host.id,
                    rows.filter { it.kind == "tmux" }.mapNotNull { decode<TmuxSession>(it.payload) },
                    rows.filter { it.kind == "acp" }.mapNotNull { decode<AcpSession>(it.payload) },
                    rows.filter { it.kind == "windows" }.associate { it.sessionId to (decode<List<Int>>(it.payload) ?: emptyList()) },
                )
            }.onStart { emit(CachedSessionList(host.id)) }
        }.stateIn(scope, SharingStarted.Eagerly, CachedSessionList())

    init {
        scope.launch {
            for (signal in historyWriteSignals) {
                while (true) {
                    val pending = synchronized(pendingWriteLock) {
                        pendingWrites.entries.firstOrNull()?.let { entry -> pendingWrites.remove(entry.key) }
                    } ?: break
                    for (write in listOfNotNull(pending.authoritative, pending.latest)) {
                        try { saveHistory(write.target, write.messages, write.complete, write.conversationKey, write.tool) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Log.w("OfflineCache", "Could not save chat history", error)
                        }
                    }
                    yield()
                }
            }
        }
        scope.launch {
            webSocket.messages.collect { message ->
                if (message["type"] !in setOf("sessions-list", "session_list", "acp-sessions-listed")) return@collect
                val host = hosts.getSelectedHostOnce() ?: return@collect
                val source = message["_sourceServerUrl"] ?: webSocket.currentWebSocketUrl?.substringBefore('?')
                if (source != "${host.wsUrl}/ws") return@collect
                try {
                    cacheSessionList(host.id, message)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w("OfflineCache", "Could not save session list", error)
                }
            }
        }
        scope.launch {
            combine(hosts.getSelectedHost().distinctUntilChangedBy { it?.id to it?.wsUrl }, webSocket.connectionStatus) { host, status -> host to status }
                .collectLatest { (host, status) ->
                    if (host == null || status != ConnectionStatus.CONNECTED) return@collectLatest
                    if (webSocket.currentWebSocketUrl?.substringBefore('?') != "${host.wsUrl}/ws") return@collectLatest
                    // Each refresh has its own read-only socket: never replace the UI's watch.
                    coroutineScope {
                        launch {
                            while (isActive) {
                                webSocket.send(mapOf("type" to "list-sessions"))
                                webSocket.send(mapOf("type" to "acp-list-sessions"))
                                syncRequests.trySend(Unit)
                                delay(60_000)
                            }
                        }
                        syncRequests.receiveAsFlow().collect {
                            // Let startup/navigation frames run before beginning backfill.
                            delay(1_500)
                            val snapshot = sessions.first { it.hostId == host.id }
                            syncHost(host, snapshot)
                        }
                    }
                }
        }
    }

    private suspend fun cacheSessionList(hostId: String, message: Map<String, Any?>) {
        val kind = when (message["type"]) {
            "sessions-list", "session_list" -> "tmux"
            "acp-sessions-listed" -> "acp"
            else -> return
        }
        val raw = message["sessions"] as? List<*> ?: return // Errors aren't empty snapshots.
        val rows = raw.mapIndexedNotNull { index, value ->
            val row = value as? Map<*, *> ?: return@mapIndexedNotNull null
            if (kind == "tmux") {
                val name = row["name"] as? String ?: return@mapIndexedNotNull null
                CachedSession(hostId, kind, name, index, json.encodeToString(TmuxSession(
                    name, row["attached"] as? Boolean ?: false, (row["windows"] as? Number)?.toInt() ?: 1,
                    row["created"] as? String, row["tool"] as? String,
                )))
            } else {
                val id = row["sessionId"] as? String ?: return@mapIndexedNotNull null
                CachedSession(hostId, kind, id, index, json.encodeToString(AcpSession(
                    id, row["cwd"] as? String ?: "", row["title"] as? String ?: "",
                    row["updatedAt"] as? String ?: "", row["provider"] as? String,
                )))
            }
        }
        val previous = dao.observeSessions(hostId).first().filter { it.kind == kind }
        if (previous != rows) {
            dao.replaceSessions(hostId, kind, rows)
            syncRequests.trySend(Unit)
        }
    }

    suspend fun history(target: ChatTarget): CachedHistory? = withContext(cacheDispatcher) {
        database.withTransaction {
            val meta = dao.conversation(target.key) ?: return@withTransaction null
            CachedHistory(meta, dao.messages(target.key).mapNotNull { decode<ChatMessage>(it.payload) })
        }
    }

    /** A bounded read window; all older messages remain on disk and can be paged in. */
    suspend fun historyPage(
        target: ChatTarget,
        beforePosition: Int? = null,
        limit: Int = 100,
        beforeMessageId: String? = null,
    ): CachedHistoryPage? = withContext(cacheDispatcher) {
        require(limit > 0)
        database.withTransaction {
            val meta = dao.conversation(target.key) ?: return@withTransaction null
            // A backfill may insert rows before an existing row. Resolve its ID
            // again rather than treating an old numeric position as permanent.
            val before = beforeMessageId?.let { dao.messagePosition(target.key, it) } ?: beforePosition
            val descending = dao.messagePage(target.key, before, limit + 1)
            val rows = descending.take(limit).asReversed()
            CachedHistoryPage(meta, rows.mapNotNull { decode<ChatMessage>(it.payload) },
                rows.firstOrNull()?.position, dao.messageCount(target.key), descending.size > limit)
        }
    }

    /** Partial refreshes retain older saved pages. Full refreshes are authoritative. */
    suspend fun saveHistory(
        target: ChatTarget,
        messages: List<ChatMessage>,
        complete: Boolean,
        conversationKey: String? = null,
        tool: String? = null,
    ) {
        recordHistoryUpdate(target.key, complete, conversationKey)
        saveHistoryWindow(target, messages, complete, conversationKey, tool)
    }

    private suspend fun saveHistoryWindow(
        target: ChatTarget,
        messages: List<ChatMessage>,
        complete: Boolean,
        conversationKey: String?,
        tool: String?,
        beforeMessageId: String? = null,
        expectedRevision: Long? = null,
    ) = withContext(cacheDispatcher) {
        val incoming = messages.distinctBy { it.id }
        // Serialize only the supplied window, outside the Room transaction. Never
        // deserialize the entire stored conversation to append one streamed reply.
        val payloads = incoming.associate { it.id to json.encodeToString(it.copy(isStreaming = false)) }
        val saved = database.withTransaction {
            if (expectedRevision != null && historyRevision(target.key) != expectedRevision) return@withTransaction false
            val old = dao.conversation(target.key)
            val sameConversation = conversationKey == null || old == null || old.conversationKey == conversationKey
            if (!sameConversation) dao.deleteMessages(target.key)
            val tail = if (sameConversation) dao.latestMessageId(target.key) else null
            val existing = incoming.map { it.id }.chunked(400).flatMap { dao.messagesByIds(target.key, it) }
                .associateBy { it.messageId }
            val knownWindow = incoming.all { it.id in existing }
            // A far-ahead latest page may leave a gap behind it. It must not keep
            // the old complete flag, or the next refresh would skip that gap.
            val continuous = incoming.isEmpty() || tail == null || tail in payloads || knownWindow
            upsertWindow(target.key, incoming.map { it.id }, payloads, existing, beforeMessageId)
            if (complete) {
                val obsolete = dao.messageIds(target.key).filterNot { it in payloads }
                for (ids in obsolete.chunked(400)) dao.deleteMessagesByIds(target.key, ids)
            }
            val next = CachedConversation(target.key, conversationKey ?: old?.conversationKey.orEmpty(), tool ?: old?.tool,
                target.cwd.ifBlank { old?.cwd.orEmpty() }, complete || (sameConversation && old?.complete == true && continuous))
            if (next != old) dao.insertConversation(next)
            true
        }
        if (!saved) return@withContext
        if (expectedRevision != null && conversationKey != null) synchronized(pendingWriteLock) {
            if ((historyRevisions[target.key] ?: 0) == expectedRevision) knownConversationKeys[target.key] = conversationKey
        }
        hosts.getHosts().first().firstOrNull { it.id == target.hostId }?.let { host ->
            attachments.enqueue("${host.httpUrl}/api/chat/files", messages)
        }
    }

    fun saveHistoryAsync(target: ChatTarget, messages: List<ChatMessage>, complete: Boolean, conversationKey: String? = null, tool: String? = null) {
        recordHistoryUpdate(target.key, complete, conversationKey)
        val write = HistoryWrite(target, messages, complete, conversationKey, tool)
        synchronized(pendingWriteLock) {
            val pending = pendingWrites.getOrPut(target.key) { PendingHistoryWrite() }
            if (complete) {
                // An authoritative clear/replacement is a barrier: a later
                // partial snapshot must run after it, never resurrect old rows.
                pending.authoritative = write
                pending.latest = null
            } else pending.latest = write
        }
        historyWriteSignals.trySend(Unit)
    }

    private fun recordHistoryUpdate(key: String, authoritative: Boolean, conversationKey: String?) {
        synchronized(pendingWriteLock) {
            val previousKey = knownConversationKeys[key]
            if (authoritative || (conversationKey != null && previousKey != null && previousKey != conversationKey)) {
                historyRevisions[key] = (historyRevisions[key] ?: 0) + 1
            }
            if (conversationKey != null) knownConversationKeys[key] = conversationKey
        }
    }

    private fun historyRevision(key: String): Long = synchronized(pendingWriteLock) { historyRevisions[key] ?: 0 }

    /** Insert/update the incoming ordered window, retaining stored rows outside it. */
    private suspend fun upsertWindow(
        key: String,
        ids: List<String>,
        payloads: Map<String, String>,
        existing: Map<String, CachedChatMessage>,
        beforeMessageId: String?,
    ) {
        if (ids.isEmpty()) return
        val positions = existing.mapValues { it.value.position }.toMutableMap()
        val bounds = dao.messageBounds(key)
        var firstPosition = bounds.firstPosition ?: 0
        var lastPosition = bounds.lastPosition ?: -1
        val beforePosition = beforeMessageId?.let { dao.messagePosition(key, it) }
        var cursor: Int? = null
        val changed = mutableListOf<CachedChatMessage>()
        var index = 0
        while (index < ids.size) {
            val id = ids[index]
            val savedPosition = positions[id]
            if (savedPosition != null) {
                var position = savedPosition
                if (cursor != null && position <= cursor) {
                    position = cursor + 1
                    if (position <= lastPosition) {
                        dao.shiftMessages(key, position, 1)
                        positions.replaceAll { _, value -> if (value >= position) value + 1 else value }
                        lastPosition++
                    }
                    positions[id] = position
                    lastPosition = maxOf(lastPosition, position)
                }
                if (existing[id]?.payload != payloads[id] || savedPosition != position) {
                    changed.add(CachedChatMessage(key, id, position, payloads.getValue(id)))
                }
                cursor = position
                index++
                continue
            }
            val start = index
            while (index < ids.size && positions[ids[index]] == null) index++
            val runLength = index - start
            val nextKnown = ids.getOrNull(index)?.let(positions::get) ?: beforePosition
            val insertion = cursor?.plus(1) ?: nextKnown?.let {
                // Ordinary older backfill uses negative positions. Existing
                // rows keep their positions and are not rewritten on each page.
                if (it == firstPosition) it - runLength else it
            } ?: (lastPosition + 1)
            if (insertion >= firstPosition && insertion <= lastPosition) {
                // Integer positions need space only for a newly discovered gap.
                // Existing message payloads stay in SQLite; no JSON roundtrip.
                dao.shiftMessages(key, insertion, runLength)
                positions.replaceAll { _, value -> if (value >= insertion) value + runLength else value }
                lastPosition += runLength
            }
            for (offset in 0 until runLength) {
                val newId = ids[start + offset]
                val position = insertion + offset
                positions[newId] = position
                changed.add(CachedChatMessage(key, newId, position, payloads.getValue(newId), existing[newId]?.syncRevision.orEmpty(),
                    existing[newId]?.takeIf { it.payload == payloads.getValue(newId) }?.serverKey.orEmpty()))
            }
            cursor = insertion + runLength - 1
            firstPosition = minOf(firstPosition, insertion)
            lastPosition = maxOf(lastPosition, cursor)
        }
        if (changed.isNotEmpty()) dao.insertMessages(changed)
    }

    private suspend fun syncHost(host: Host, snapshot: CachedSessionList) {
        val url = webSocket.currentWebSocketUrl ?: return
        if (url.substringBefore('?') != "${host.wsUrl}/ws") return
        val socket = SessionSocket(client)
        try {
            socket.connect(url)
            withTimeout(20_000) { socket.isConnected.first { it } }
            val incremental = supportsIncrementalSync(socket)
            for (session in snapshot.tmux) {
                try {
                    val windows = request(socket, mapOf("type" to "list-windows", "sessionName" to session.name)) {
                        it["type"] == "windows-list" && it["sessionName"] == session.name
                    }
                    val indexes = (windows["windows"] as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("index") as? Number)?.toInt() }
                    if (snapshot.windows[session.name] != indexes) {
                        dao.insertSessions(listOf(CachedSession(host.id, "windows", session.name, 0, json.encodeToString(indexes))))
                    }
                    for (index in indexes) {
                        try {
                            val target = ChatTarget.create(host.id, session.name, index)
                            if (incremental) syncHistoryIncremental(socket, target) else syncHistory(socket, target)
                        }
                        catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            Log.w("OfflineCache", "History refresh deferred for a terminal window", error)
                        }
                        if (incremental) yield() else delay(150)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    Log.w("OfflineCache", "History refresh deferred for a terminal session", error)
                }
            }
            for (session in snapshot.acp) {
                try {
                    val target = ChatTarget.create(host.id, session.sessionId, isAcp = true, cwd = session.cwd)
                    if (incremental) syncHistoryIncremental(socket, target) else syncHistory(socket, target)
                }
                catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    Log.w("OfflineCache", "History refresh deferred for a direct session", error)
                }
                if (incremental) yield() else delay(150)
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            Log.w("OfflineCache", "Offline history refresh deferred", error)
        } finally { socket.dispose() }
    }

    private suspend fun supportsIncrementalSync(socket: SessionSocket): Boolean = try {
        withTimeout(1_500) {
            request(socket, mapOf("type" to "get-history-sync-capabilities")) {
                it["type"] == "history-sync-capabilities" || it["type"] == "error"
            }.let { (it["version"] as? Number)?.toInt() == 1 }
        }
    } catch (error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        false
    }

    /** Fetch finite disk-backed snapshots; never establish live archive watchers. */
    internal suspend fun syncHistoryIncremental(socket: SessionSocket, target: ChatTarget) {
        val session = if (target.isAcp) "acp_${target.sessionName}" else target.sessionName
        val requestId = java.util.UUID.randomUUID().toString()
        val state = request(socket, mapOf("type" to "get-history-state", "requestId" to requestId,
            "sessionName" to session, "windowIndex" to target.windowIndex)) { it["type"] == "history-state" && it["requestId"] == requestId }
        if (state["available"] != true) return // An unavailable source never erases a local archive.
        val currentRevision = state["revision"] as? String ?: return
        val existing = database.withTransaction { dao.historySync(target.key) to dao.conversation(target.key) }
        if (existing.first?.revision == currentRevision && existing.first?.complete == true && existing.second?.complete == true) return
        // Finish a pinned snapshot before catching up with concurrent appends.
        // A busy conversation must not restart its oldest-page download forever.
        var progress = existing.first?.takeIf { it.revision == currentRevision || !it.complete }
            ?: CachedHistorySync(target.key, currentRevision,
                baseRevision = existing.first?.takeIf { it.complete && it.keyed }?.revision)
        val revision = progress.revision
        val localRevision = historyRevision(target.key)
        val conversationKey = state["conversationKey"] as? String
        repeat(4) {
            if (historyRevision(target.key) != localRevision) return
            val id = java.util.UUID.randomUUID().toString()
            val command = mapOf("type" to "get-history-page", "requestId" to id,
                "sessionName" to session, "windowIndex" to target.windowIndex, "revision" to revision,
                "offset" to progress.offset, "limit" to if (progress.offset == 0) 30 else 200,
                "baseRevision" to progress.baseRevision)
            suspend fun fetch(command: Map<String, Any?>) = request(socket, command) {
                it["type"] == "history-page" && it["requestId"] == command["requestId"]
            }
            var page = fetch(command)
            if (page["error"] != null || page["revision"] != revision) {
                if (revision != currentRevision && page["error"]?.toString()?.contains("changed", ignoreCase = true) == true) {
                    dao.saveHistorySync(CachedHistorySync(target.key, currentRevision))
                    syncRequests.trySend(Unit)
                }
                return
            }
            @Suppress("UNCHECKED_CAST")
            fun entries(page: Map<String, Any?>) = (page["entries"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
            var entries = entries(page)
            var known = entries?.mapNotNull { it["key"] as? String }?.chunked(400)?.flatMap {
                dao.serverMessagesByKeys(target.key, it)
            }?.associate { it.serverKey to it.messageId }.orEmpty()
            if (entries?.any { it["message"] == null && it["key"] !in known } == true) {
                // A local edit or missing base snapshot can invalidate elision.
                // Re-fetch this finite page with all bodies before acknowledging it.
                page = fetch(command + mapOf("requestId" to java.util.UUID.randomUUID().toString(), "baseRevision" to null))
                if (page["error"] != null || page["revision"] != revision) return
                entries = entries(page)
                known = emptyMap()
            }
            val downloaded = if (entries == null) emptyMap() else entries.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                (entry["message"] as? Map<String, Any?>)?.let { ChatMessageParser.parse(it) }?.let { (entry["key"] as String) to it }
            }.toMap()
            val messages = if (entries == null) parseMessages(page) else downloaded.values.toList()
            val count = entries?.size ?: messages.size
            val hasMore = page["hasMore"] == true
            if (hasMore && count == 0) return
            if (entries?.any { it["key"] !in downloaded && it["key"] !in known } == true) return
            saveHistoryWindow(target, messages, false, conversationKey, page["tool"] as? String,
                beforeMessageId = if (entries == null) progress.beforeMessageId else null, expectedRevision = localRevision)
            val next = progress.copy(offset = progress.offset + count,
                beforeMessageId = messages.firstOrNull()?.id ?: progress.beforeMessageId, complete = !hasMore,
                keyed = entries != null)
            val committed = database.withTransaction {
                if (historyRevision(target.key) != localRevision) return@withTransaction false
                if (entries == null) {
                    for (ids in messages.map { it.id }.chunked(400)) dao.markSyncedMessages(target.key, ids, revision)
                } else {
                    for (entry in entries) {
                        val key = entry["key"] as String
                        val messageId = downloaded[key]?.id ?: known[key] ?: return@withTransaction false
                        val position = (entry["position"] as? Number)?.toInt() ?: return@withTransaction false
                        check(dao.assignSyncedMessage(target.key, messageId, key, position, revision) == 1)
                    }
                }
                if (!hasMore) {
                    dao.removeUnsyncedMessages(target.key, revision)
                    dao.conversation(target.key)?.let { dao.insertConversation(it.copy(complete = true)) }
                }
                dao.saveHistorySync(next)
                true
            }
            if (!committed) return
            if (!hasMore) {
                if (revision != currentRevision) syncRequests.trySend(Unit)
                return
            }
            progress = next
            delay(100)
        }
        // A conflated continuation lets other conversations and live UI work run.
        syncRequests.trySend(Unit)
    }

    private suspend fun syncHistory(socket: SessionSocket, target: ChatTarget) {
        val sessionName = if (target.isAcp) "acp_${target.sessionName}" else target.sessionName
        // The usual refresh only needs a small head page. A larger page size is
        // reserved for backfilling a newly discovered gap or the initial cache.
        val watch = if (target.isAcp) mapOf("type" to "watch-acp-chat-log", "sessionId" to target.sessionName, "limit" to 30)
            else mapOf("type" to "watch-chat-log", "sessionName" to target.sessionName, "windowIndex" to target.windowIndex, "limit" to 30)
        val baseline = database.withTransaction { dao.conversation(target.key) to dao.latestMessageId(target.key) }
        val baselineKey = baseline.first?.conversationKey?.takeIf { it.isNotBlank() }
        val readyToSync = synchronized(pendingWriteLock) {
            if (baselineKey != null) knownConversationKeys.putIfAbsent(target.key, baselineKey)
            val known = knownConversationKeys[target.key]
            known == null || known == baselineKey
        }
        if (!readyToSync) return // A queued conversation switch must be written first.
        val revision = historyRevision(target.key)
        fun matches(message: Map<String, Any?>): Boolean =
            message["sessionName"] == sessionName && (message["windowIndex"] as? Number)?.toInt() == target.windowIndex
        var response = request(socket, watch) {
            (it["type"] == "chat-history" && matches(it)) || it["type"] == "chat-log-error" ||
                (it["type"] == "chat-binding" && matches(it) && (it["state"] as? Map<*, *>)?.get("status") in setOf("required", "unavailable", "unsupported"))
        }
        if (response["type"] != "chat-history") return
        val bindingId = response["bindingId"] as? String
        val conversationKey = response["conversationKey"] as? String
        val tool = response["tool"] as? String
        var page = parseMessages(response)
        val baselineContinuous = baseline.first?.complete == true &&
            (conversationKey == null || baseline.first?.conversationKey == conversationKey)
        val baselineTail = baseline.second?.takeIf { baselineContinuous }
        try {
            if (historyRevision(target.key) != revision) return
            if (response["hasMore"] != true) {
                saveHistoryWindow(target, page, complete = true, conversationKey, tool, expectedRevision = revision)
                return
            }
            saveBackgroundPage(target, page, conversationKey, tool, expectedRevision = revision)
            if (baselineTail != null && page.any { it.id == baselineTail }) {
                finishBackfill(target, conversationKey, expectedRevision = revision)
                return
            }
            var offset = page.size
            var previousFirstId = page.firstOrNull()?.id
            while (response["hasMore"] == true) {
                delay(100) // Cooperative backfill: release CPU/IO between pages.
                response = request(socket, mapOf(
                    "type" to if (bindingId == null) "load-more-chat-history" else "load-more-bound-chat-history",
                    "sessionName" to sessionName, "windowIndex" to target.windowIndex,
                    "bindingId" to bindingId, "offset" to offset, "limit" to 200,
                )) { (it["type"] == "chat-history-chunk" && matches(it) && it["bindingId"] == bindingId) || it["type"] == "chat-log-error" }
                if (response["type"] != "chat-history-chunk") break
                if (historyRevision(target.key) != revision) break
                page = parseMessages(response)
                if (page.isEmpty() || page.firstOrNull()?.id == previousFirstId) break
                val beforeMessageId = previousFirstId
                previousFirstId = page.firstOrNull()?.id
                offset += page.size
                saveBackgroundPage(target, page, conversationKey, tool, beforeMessageId, revision)
                if (response["hasMore"] != true) {
                    finishBackfill(target, conversationKey, firstMessageId = page.first().id, expectedRevision = revision)
                    break
                }
                if (baselineTail != null && page.any { it.id == baselineTail }) {
                    // A previously complete cache is now connected to the new
                    // head. Its unchanged older pages need not be downloaded.
                    finishBackfill(target, conversationKey, expectedRevision = revision)
                    break
                }
            }
        } finally {
            socket.send(mapOf("type" to "unwatch-chat-log"))
        }
    }

    private suspend fun saveBackgroundPage(target: ChatTarget, page: List<ChatMessage>, conversationKey: String?, tool: String?, beforeMessageId: String? = null, expectedRevision: Long) {
        saveHistoryWindow(target, page, complete = false, conversationKey, tool, beforeMessageId, expectedRevision)
        if (page.isEmpty()) return
        database.withTransaction {
            if (historyRevision(target.key) != expectedRevision) return@withTransaction
            val current = dao.conversation(target.key) ?: return@withTransaction
            if (conversationKey != null && current.conversationKey != conversationKey) return@withTransaction
            val first = dao.messagePosition(target.key, page.first().id) ?: return@withTransaction
            val last = dao.messagePosition(target.key, page.last().id) ?: return@withTransaction
            // A remote page is a contiguous authoritative range, unlike a UI
            // snapshot which may also contain unconfirmed optimistic messages.
            dao.deleteMissingInPage(target.key, first, last, page.map { it.id })
        }
    }

    private suspend fun finishBackfill(target: ChatTarget, conversationKey: String?, firstMessageId: String? = null, expectedRevision: Long) {
        database.withTransaction {
            if (historyRevision(target.key) != expectedRevision) return@withTransaction
            val current = dao.conversation(target.key) ?: return@withTransaction
            if (conversationKey != null && current.conversationKey != conversationKey) return@withTransaction
            if (firstMessageId != null) dao.messagePosition(target.key, firstMessageId)?.let {
                dao.deleteBeforePosition(target.key, it)
            }
            if (!current.complete) dao.insertConversation(current.copy(complete = true))
        }
    }

    private suspend fun request(socket: SessionSocket, command: Map<String, Any?>, accepts: (Map<String, Any?>) -> Boolean): Map<String, Any?> = coroutineScope {
        withTimeout(20_000) {
            val response = async(start = CoroutineStart.UNDISPATCHED) { socket.messages.first(accepts) }
            socket.send(command)
            response.await()
        }
    }

    private fun parseMessages(message: Map<String, Any?>): List<ChatMessage> = (message["messages"] as? List<*>).orEmpty().mapNotNull {
        @Suppress("UNCHECKED_CAST")
        (it as? Map<String, Any?>)?.let(ChatMessageParser::parse)
    }

    private inline fun <reified T> decode(value: String): T? = runCatching { json.decodeFromString<T>(value) }.getOrNull()

    internal fun close() { scope.cancel() }
}

/** Keep server order, update stable IDs, and retain the older prefix on a partial refresh. */
internal fun mergeCachedMessages(previous: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
    if (previous.isEmpty()) return incoming.distinctBy { it.id }
    if (incoming.isEmpty()) return previous
    val incomingIds = incoming.mapTo(hashSetOf()) { it.id }
    val anchor = previous.indexOfFirst { it.id in incomingIds }
    if (anchor >= 0) {
        val lastAnchor = previous.indexOfLast { it.id in incomingIds }
        return (previous.take(anchor) + incoming + previous.drop(lastAnchor + 1)).distinctBy { it.id }
    }
    val (before, after) = previous.partition { it.timestamp < incoming.first().timestamp }
    return (before + incoming + after).distinctBy { it.id }
}
