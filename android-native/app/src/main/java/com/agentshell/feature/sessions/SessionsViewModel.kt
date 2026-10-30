package com.agentshell.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.FavoriteSessionDao
import com.agentshell.data.local.SessionTagDao
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.FavoriteSession
import com.agentshell.data.model.SessionTag
import com.agentshell.data.model.SessionTagAssignment
import com.agentshell.data.model.TmuxSession
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus
import com.agentshell.data.model.AgentCompletionReceipt
import com.agentshell.data.model.ChatReadState
import com.agentshell.data.repository.ChatActivityRepository
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.remote.WebSocketService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.agentshell.data.remote.acpCreateSession
import com.agentshell.data.remote.acpListSessions
import com.agentshell.data.remote.addFavorite
import com.agentshell.data.remote.addTag
import com.agentshell.data.remote.assignTagToSession
import com.agentshell.data.remote.createSession
import com.agentshell.data.remote.deleteAcpSession
import com.agentshell.data.remote.deleteFavorite
import com.agentshell.data.remote.deleteTag
import com.agentshell.data.remote.getFavorites
import com.agentshell.data.remote.getSessionCwd
import com.agentshell.data.remote.getTagAssignments
import com.agentshell.data.remote.getTags
import com.agentshell.data.remote.killSession
import com.agentshell.data.remote.removeTagFromSession
import com.agentshell.data.remote.requestSessions
import com.agentshell.data.remote.selectBackend
import com.agentshell.data.remote.setFavoriteTags
import com.agentshell.data.remote.updateFavorite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
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
import javax.inject.Inject

@Serializable
private data class FavoriteExportEntry(
    val name: String,
    val path: String,
    val sortOrder: Int = 0,
    val startupCommand: String? = null,
    val startupArgs: String? = null,
)

@Serializable
private data class FavoritesExport(
    val version: Int = 1,
    val favorites: List<FavoriteExportEntry>,
)

private val favoritesJson = Json { ignoreUnknownKeys = true }

data class SessionsUiState(
    val tmuxSessions: List<TmuxSession> = emptyList(),
    val acpSessions: List<AcpSession> = emptyList(),
    val favorites: List<FavoriteSession> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedBackend: String = "tmux",
    val selectedSessionIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val tags: List<SessionTag> = emptyList(),
    val sessionTagMap: Map<String, List<SessionTag>> = emptyMap(),
    val activeTagFilter: String? = null,
    /** Maps favoriteId → list of tag IDs assigned to that favorite. */
    val favoriteTagMap: Map<String, List<String>> = emptyMap(),
    val tmuxActivities: Map<String, AgentActivity> = emptyMap(),
    val directActivities: Map<String, AgentActivity> = emptyMap(),
    val activityNow: Long = 0,
    val isConnected: Boolean = false,
    val finishedWindowIndices: Map<String, Int> = emptyMap(),
    val cachedWindowIndices: Map<String, List<Int>> = emptyMap(),
)

/** Emitted when a new ACP session is created so the UI can navigate to it. */
data class NewAcpSessionEvent(val sessionId: String, val cwd: String)

private data class SessionCwdResult(
    val sessionName: String,
    val cwd: String,
)

private data class PendingTagSnapshot(
    val tags: List<SessionTag>? = null,
    val assignments: List<SessionTagAssignment>? = null,
)

internal data class SessionActivitySummary(
    val tmux: Map<String, AgentActivity>,
    val direct: Map<String, AgentActivity>,
    val finishedWindows: Map<String, Int>,
)

/** Reuse the repositories' stable keys instead of re-hashing every target on every heartbeat. */
internal fun summarizeSessionActivities(
    hostId: String?,
    activities: Map<String, AgentActivity>,
    states: Map<String, ChatReadState>,
    completions: Map<String, AgentCompletionReceipt>,
    now: Long,
): SessionActivitySummary {
    val targets = linkedMapOf<String, com.agentshell.data.model.ChatTarget>()
    for ((key, state) in states) if (state.target.hostId == hostId) targets[key] = state.target
    for ((key, receipt) in completions) if (receipt.target.hostId == hostId) targets.putIfAbsent(key, receipt.target)
    val tmux = linkedMapOf<String, AgentActivity>()
    val direct = linkedMapOf<String, AgentActivity>()
    val newestCompletion = mutableMapOf<String, AgentCompletionReceipt>()
    for ((key, target) in targets) {
        val completion = completions[key]?.takeUnless { it.acknowledged }
        val live = activities[key]?.let {
            if (it.expired(now)) it.unknown("Live status is unavailable") else it
        }
        // An unread completion belongs to a previous turn, not the live status.
        val activity = when {
            completion == null -> live
            live == null || live.status == AgentActivityStatus.IDLE -> completion.asActivity()
            else -> live.copy(unreadCompletion = true)
        }
        if (activity != null) {
            if (target.isAcp) direct[target.sessionName] = activity
            else if (tmux[target.sessionName]?.let { activityPriority(it) >= activityPriority(activity) } != true) {
                tmux[target.sessionName] = activity
            }
        }
        if (!target.isAcp && completion != null &&
            (newestCompletion[target.sessionName]?.finishedAt ?: Long.MIN_VALUE) < completion.finishedAt
        ) newestCompletion[target.sessionName] = completion
    }
    // Keep reminders from other windows without replacing the active window.
    for (session in newestCompletion.keys) {
        tmux[session]?.let { tmux[session] = it.copy(unreadCompletion = true) }
    }
    return SessionActivitySummary(tmux, direct, newestCompletion.mapValues { it.value.target.windowIndex })
}

enum class FavoriteLaunchTarget {
    TMUX,
    ACP,
    DIRECT,
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val wsService: WebSocketService,
    private val favoriteSessionDao: FavoriteSessionDao,
    private val sessionTagDao: SessionTagDao,
    private val activityRepository: ChatActivityRepository,
    private val hostRepository: HostRepository,
    private val offlineCache: com.agentshell.data.repository.OfflineCacheRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _newAcpSession = MutableSharedFlow<NewAcpSessionEvent>(extraBufferCapacity = 8)
    val newAcpSession: SharedFlow<NewAcpSessionEvent> = _newAcpSession.asSharedFlow()
    private val _sessionCwdResults = MutableSharedFlow<SessionCwdResult>(extraBufferCapacity = 8)
    private var pendingTagSnapshot = PendingTagSnapshot()
    private val sessionCwdCache = linkedMapOf<String, String>()
    private var scheduledSessionRefreshJob: Job? = null
    private var activityTicker: Job? = null

    init {
        viewModelScope.launch {
            offlineCache.sessions.collect { cached ->
                _uiState.update { it.copy(tmuxSessions = cached.tmux, acpSessions = cached.acp, cachedWindowIndices = cached.windows, isLoading = false) }
            }
        }
        observeCachedFavorites()
        observeCachedTags()
        observeMessages()
        observeConnection()
        viewModelScope.launch {
            combine(
                activityRepository.agentActivities,
                activityRepository.states,
                hostRepository.getSelectedHost().distinctUntilChangedBy { it?.id },
                activityRepository.completionReceipts,
            ) { activities, states, host, completions ->
                summarizeSessionActivities(host?.id, activities, states, completions, android.os.SystemClock.elapsedRealtime())
            }.distinctUntilChanged().flowOn(Dispatchers.Default).collect { summary ->
                _uiState.update { it.copy(tmuxActivities = summary.tmux, directActivities = summary.direct, finishedWindowIndices = summary.finishedWindows) }
            }
        }
    }

    fun setListVisible(visible: Boolean) {
        activityRepository.setListViewing("sessions-list", visible)
        if (visible && activityTicker?.isActive != true) activityTicker = viewModelScope.launch {
            while (true) { _uiState.update { it.copy(activityNow = android.os.SystemClock.elapsedRealtime()) }; delay(1000) }
        }
        if (!visible) { activityTicker?.cancel(); activityTicker = null }
    }
    override fun onCleared() { activityRepository.setListViewing("sessions-list", false); super.onCleared() }

    private fun observeCachedFavorites() {
        viewModelScope.launch {
            favoriteSessionDao.getAll().collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }
    }

    private fun observeCachedTags() {
        viewModelScope.launch {
            combine(
                sessionTagDao.getAllTags(),
                sessionTagDao.getAllAssignments(),
            ) { tags, assignments ->
                tags to assignments
            }.collect { (tags, assignments) ->
                _uiState.update {
                    it.copy(
                        tags = tags,
                        sessionTagMap = buildSessionTagMap(tags, assignments),
                    )
                }
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { message ->
                when (message["type"] as? String) {
                    "acp-session-created" -> {
                        val sessionId = message["sessionId"] as? String
                        val cwd = message["cwd"] as? String
                        if (sessionId != null && cwd != null) {
                            _uiState.update { it.copy(error = null) }
                            scheduleSessionListRefresh(delayMs = 300)
                            _newAcpSession.emit(NewAcpSessionEvent(sessionId, cwd))
                        }
                    }

                    "acp-error" -> {
                        val error = message["message"] as? String ?: "Failed to create direct session"
                        _uiState.update { it.copy(error = error, isLoading = false) }
                    }

                    "acp-session-deleted" -> {
                        val sessionId = message["sessionId"] as? String
                        if (sessionId != null) {
                            _uiState.update { state ->
                                state.copy(
                                    acpSessions = state.acpSessions.filter { it.sessionId != sessionId },
                                )
                            }
                        }
                    }

                    "session-created" -> {
                        scheduleSessionListRefresh(delayMs = 350)
                    }

                    "session-cwd" -> {
                        val sessionName = message["sessionName"] as? String ?: return@collect
                        val cwd = message["cwd"] as? String ?: return@collect
                        sessionCwdCache[sessionName] = cwd
                        _sessionCwdResults.emit(SessionCwdResult(sessionName = sessionName, cwd = cwd))
                    }

                    "favorites-list" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["favorites"] as? List<Map<String, Any?>> ?: emptyList()
                        val favorites = rawList.map { f ->
                            FavoriteSession(
                                id = f["id"] as? String ?: "",
                                name = f["name"] as? String ?: "",
                                path = f["path"] as? String ?: "",
                                sortOrder = (f["sortOrder"] as? Number)?.toInt() ?: 0,
                                startupCommand = f["startupCommand"] as? String,
                                startupArgs = f["startupArgs"] as? String,
                                createdAt = (f["createdAt"] as? Number)?.toLong() ?: 0L,
                            )
                        }
                        val favoriteTagMap = rawList.associate { f ->
                            val id = f["id"] as? String ?: ""
                            @Suppress("UNCHECKED_CAST")
                            val tagIds = (f["tagIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            id to tagIds
                        }
                        viewModelScope.launch { favoriteSessionDao.replaceAll(favorites) }
                        _uiState.update { it.copy(favorites = favorites, favoriteTagMap = favoriteTagMap) }
                    }

                    "favorite-added" -> {
                        @Suppress("UNCHECKED_CAST")
                        val f = message["favorite"] as? Map<String, Any?> ?: return@collect
                        val fav = FavoriteSession(
                            id = f["id"] as? String ?: "",
                            name = f["name"] as? String ?: "",
                            path = f["path"] as? String ?: "",
                            sortOrder = (f["sortOrder"] as? Number)?.toInt() ?: 0,
                            startupCommand = f["startupCommand"] as? String,
                            startupArgs = f["startupArgs"] as? String,
                            createdAt = (f["createdAt"] as? Number)?.toLong() ?: 0L,
                        )
                        @Suppress("UNCHECKED_CAST")
                        val tagIds = (f["tagIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        viewModelScope.launch { favoriteSessionDao.upsert(fav) }
                        _uiState.update { state ->
                            state.copy(
                                favorites = state.favorites + fav,
                                favoriteTagMap = state.favoriteTagMap + (fav.id to tagIds),
                            )
                        }
                    }

                    "favorite-updated" -> {
                        @Suppress("UNCHECKED_CAST")
                        val f = message["favorite"] as? Map<String, Any?> ?: return@collect
                        val fav = FavoriteSession(
                            id = f["id"] as? String ?: "",
                            name = f["name"] as? String ?: "",
                            path = f["path"] as? String ?: "",
                            sortOrder = (f["sortOrder"] as? Number)?.toInt() ?: 0,
                            startupCommand = f["startupCommand"] as? String,
                            startupArgs = f["startupArgs"] as? String,
                            createdAt = (f["createdAt"] as? Number)?.toLong() ?: 0L,
                        )
                        @Suppress("UNCHECKED_CAST")
                        val tagIds = (f["tagIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        viewModelScope.launch { favoriteSessionDao.upsert(fav) }
                        _uiState.update { state ->
                            state.copy(
                                favorites = state.favorites.map { if (it.id == fav.id) fav else it },
                                favoriteTagMap = state.favoriteTagMap + (fav.id to tagIds),
                            )
                        }
                    }

                    "favorite-deleted" -> {
                        val id = message["id"] as? String ?: return@collect
                        viewModelScope.launch { favoriteSessionDao.deleteById(id) }
                        _uiState.update { state ->
                            state.copy(
                                favorites = state.favorites.filter { it.id != id },
                                favoriteTagMap = state.favoriteTagMap - id,
                            )
                        }
                    }

                    "favorite-tags-updated" -> {
                        val favoriteId = message["favoriteId"] as? String ?: return@collect
                        @Suppress("UNCHECKED_CAST")
                        val tagIds = (message["tagIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        _uiState.update { state ->
                            state.copy(favoriteTagMap = state.favoriteTagMap + (favoriteId to tagIds))
                        }
                    }

                    "tags-list" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["tags"] as? List<Map<String, Any?>> ?: emptyList()
                        val tags = rawList.map { t ->
                            SessionTag(
                                id = t["id"] as? String ?: "",
                                name = t["name"] as? String ?: "",
                                colorHex = t["colorHex"] as? String ?: "#2196F3",
                                createdAt = (t["createdAt"] as? Number)?.toLong() ?: 0L,
                            )
                        }
                        pendingTagSnapshot = pendingTagSnapshot.copy(tags = tags)
                        applyPendingTagSnapshotIfReady()
                    }

                    "tag-added" -> {
                        @Suppress("UNCHECKED_CAST")
                        val t = message["tag"] as? Map<String, Any?> ?: return@collect
                        val tag = SessionTag(
                            id = t["id"] as? String ?: "",
                            name = t["name"] as? String ?: "",
                            colorHex = t["colorHex"] as? String ?: "#2196F3",
                            createdAt = (t["createdAt"] as? Number)?.toLong() ?: 0L,
                        )
                        sessionTagDao.upsertTag(tag)
                        pendingTagSnapshot = pendingTagSnapshot.copy(
                            tags = (pendingTagSnapshot.tags ?: _uiState.value.tags)
                                .filterNot { it.id == tag.id } + tag,
                        )
                        _uiState.update { state ->
                            state.copy(tags = state.tags.filterNot { it.id == tag.id } + tag)
                        }
                    }

                    "tag-deleted" -> {
                        val id = message["id"] as? String ?: return@collect
                        val tag = _uiState.value.tags.find { it.id == id }
                        if (tag != null) {
                            sessionTagDao.deleteTag(tag)
                        }
                        pendingTagSnapshot = pendingTagSnapshot.copy(
                            tags = pendingTagSnapshot.tags?.filterNot { it.id == id },
                            assignments = pendingTagSnapshot.assignments?.filterNot { it.tagId == id },
                        )
                        _uiState.update { state ->
                            state.copy(
                                tags = state.tags.filter { it.id != id },
                                sessionTagMap = state.sessionTagMap.mapValues { (_, tags) ->
                                    tags.filter { it.id != id }
                                },
                                favoriteTagMap = state.favoriteTagMap.mapValues { (_, ids) ->
                                    ids.filter { it != id }
                                },
                            )
                        }
                    }

                    "tag-assignments-list" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["assignments"] as? List<Map<String, Any?>> ?: emptyList()
                        val assignments = rawList.mapNotNull { assignment ->
                            val sessionName = assignment["sessionName"] as? String ?: return@mapNotNull null
                            val tagId = assignment["tagId"] as? String ?: return@mapNotNull null
                            SessionTagAssignment(sessionName = sessionName, tagId = tagId)
                        }
                        pendingTagSnapshot = pendingTagSnapshot.copy(assignments = assignments)
                        applyPendingTagSnapshotIfReady()
                    }

                    "tag-assignment-updated" -> {
                        val sessionName = message["sessionName"] as? String ?: return@collect
                        val tagId = message["tagId"] as? String ?: return@collect
                        val assigned = message["assigned"] as? Boolean ?: return@collect
                        val tag = _uiState.value.tags.find { it.id == tagId }
                        if (assigned && tag == null) {
                            pendingTagSnapshot = pendingTagSnapshot.copy(assignments = null)
                            wsService.getTags()
                            wsService.getTagAssignments()
                            return@collect
                        }
                        if (assigned) {
                            sessionTagDao.assignTag(SessionTagAssignment(sessionName = sessionName, tagId = tagId))
                            pendingTagSnapshot = pendingTagSnapshot.copy(
                                assignments = updatePendingAssignments(
                                    sessionName = sessionName,
                                    tagId = tagId,
                                    assigned = true,
                                ),
                            )
                        } else {
                            sessionTagDao.removeTag(sessionName, tagId)
                            pendingTagSnapshot = pendingTagSnapshot.copy(
                                assignments = updatePendingAssignments(
                                    sessionName = sessionName,
                                    tagId = tagId,
                                    assigned = false,
                                ),
                            )
                        }
                        _uiState.update { state ->
                            val current = state.sessionTagMap[sessionName]?.toMutableList() ?: mutableListOf()
                            if (assigned && tag != null && current.none { it.id == tagId }) {
                                current.add(tag)
                            } else if (!assigned) {
                                current.removeAll { it.id == tagId }
                            }
                            state.copy(sessionTagMap = state.sessionTagMap + (sessionName to current))
                        }
                    }
                }
            }
        }
    }

    private suspend fun applyPendingTagSnapshotIfReady() {
        val tags = pendingTagSnapshot.tags ?: return
        val assignments = pendingTagSnapshot.assignments ?: return
        val validTagIds = tags.asSequence()
            .map(SessionTag::id)
            .toSet()
        val validAssignments = assignments.filter { it.tagId in validTagIds }
        val tagMap = buildSessionTagMap(tags, validAssignments)

        sessionTagDao.replaceTagsAndAssignments(tags, validAssignments)
        _uiState.update { it.copy(tags = tags, sessionTagMap = tagMap) }
        pendingTagSnapshot = pendingTagSnapshot.copy(assignments = validAssignments)
    }

    private fun buildSessionTagMap(
        tags: List<SessionTag>,
        assignments: List<SessionTagAssignment>,
    ): Map<String, List<SessionTag>> {
        val tagById = tags.associateBy { it.id }
        return assignments
            .groupBy(SessionTagAssignment::sessionName)
            .mapValues { (_, items) ->
                items.mapNotNull { tagById[it.tagId] }
            }
    }

    private fun updatePendingAssignments(
        sessionName: String,
        tagId: String,
        assigned: Boolean,
    ): List<SessionTagAssignment> {
        val currentAssignments = pendingTagSnapshot.assignments ?: emptyList()
        return if (assigned) {
            if (currentAssignments.any { it.sessionName == sessionName && it.tagId == tagId }) {
                currentAssignments
            } else {
                currentAssignments + SessionTagAssignment(sessionName = sessionName, tagId = tagId)
            }
        } else {
            currentAssignments.filterNot { it.sessionName == sessionName && it.tagId == tagId }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            wsService.connectionStatus.collect { status ->
                val connected = status == com.agentshell.data.model.ConnectionStatus.CONNECTED
                _uiState.update { it.copy(isConnected = connected, isLoading = if (connected) it.isLoading else false) }
                if (connected) {
                    requestSessions(
                        includeMetadata = true,
                        showLoading = _uiState.value.tmuxSessions.isEmpty() && _uiState.value.acpSessions.isEmpty(),
                    )
                }
            }
        }
    }

    fun requestSessions(
        includeMetadata: Boolean = true,
        showLoading: Boolean = true,
    ) {
        scheduledSessionRefreshJob?.cancel()
        if (!wsService.isConnected) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        if (showLoading && _uiState.value.tmuxSessions.isEmpty() && _uiState.value.acpSessions.isEmpty()) {
            _uiState.update { it.copy(isLoading = true) }
        }
        wsService.requestSessions()
        wsService.acpListSessions()
        if (includeMetadata) {
            wsService.getFavorites()
            wsService.getTags()
            wsService.getTagAssignments()
        }
    }

    private fun scheduleSessionListRefresh(delayMs: Long = 350L) {
        scheduledSessionRefreshJob?.cancel()
        scheduledSessionRefreshJob = viewModelScope.launch {
            delay(delayMs)
            requestSessions(includeMetadata = false, showLoading = false)
        }
    }

    fun createSession(name: String) {
        _uiState.update { it.copy(isLoading = true) }
        wsService.createSession(name)
        scheduleSessionListRefresh(delayMs = 350)
    }

    fun createAcpSession(cwd: String, backend: String = "opencode") {
        _uiState.update { it.copy(error = null) }
        wsService.selectBackend(backend)
        wsService.acpCreateSession(cwd, backend)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun killSession(sessionName: String) {
        wsService.killSession(sessionName)
        scheduleSessionListRefresh(delayMs = 350)
    }

    fun deleteAcpSession(sessionId: String) {
        wsService.selectBackend("acp")
        wsService.deleteAcpSession(sessionId)
        scheduleSessionListRefresh(delayMs = 350)
    }

    fun deleteSelectedSessions() {
        val ids = _uiState.value.selectedSessionIds.toList()
        ids.forEach { id ->
            wsService.selectBackend("acp")
            wsService.deleteAcpSession(id)
        }
        scheduleSessionListRefresh(delayMs = 350)
        exitSelectionMode()
    }

    fun toggleSelection(sessionId: String) {
        _uiState.update { state ->
            val newIds = state.selectedSessionIds.toMutableSet()
            if (newIds.contains(sessionId)) newIds.remove(sessionId) else newIds.add(sessionId)
            state.copy(selectedSessionIds = newIds, isSelectionMode = newIds.isNotEmpty())
        }
    }

    fun enterSelectionMode(sessionId: String) {
        _uiState.update { state ->
            state.copy(isSelectionMode = true, selectedSessionIds = setOf(sessionId))
        }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    fun switchBackend(backend: String) {
        _uiState.update { it.copy(selectedBackend = backend) }
    }

    fun getCachedSessionPath(sessionName: String): String? = sessionCwdCache[sessionName]

    suspend fun resolveTmuxSessionPath(sessionName: String): String? = coroutineScope {
        sessionCwdCache[sessionName]?.let { return@coroutineScope it }
        val pendingResult = async {
            withTimeoutOrNull(3000L) {
                _sessionCwdResults.first { it.sessionName == sessionName }.cwd
            }
        }
        wsService.getSessionCwd(sessionName)
        pendingResult.await()
    }

    // ── Favorites CRUD ────────────────────────────────────────────────────────

    fun addFavorite(name: String, path: String, startupCommand: String?, startupArgs: String?, tagIds: List<String> = emptyList()) {
        wsService.addFavorite(name = name, path = path, startupCommand = startupCommand, startupArgs = startupArgs, tagIds = tagIds)
    }

    fun updateFavorite(favorite: FavoriteSession, newName: String, newPath: String, newStartupCommand: String?, newStartupArgs: String?, tagIds: List<String> = emptyList()) {
        wsService.updateFavorite(
            id = favorite.id,
            name = newName,
            path = newPath,
            sortOrder = favorite.sortOrder,
            startupCommand = newStartupCommand,
            startupArgs = newStartupArgs,
            tagIds = tagIds,
        )
    }

    fun deleteFavorite(favorite: FavoriteSession) {
        wsService.deleteFavorite(favorite.id)
    }

    fun setFavoriteTags(favoriteId: String, tagIds: List<String>) {
        wsService.setFavoriteTags(favoriteId, tagIds)
    }

    // ── Favorites Import / Export ─────────────────────────────────────────────

    suspend fun exportFavoritesJson(): String {
        val all = _uiState.value.favorites
        val entries = all.map {
            FavoriteExportEntry(
                name = it.name,
                path = it.path,
                sortOrder = it.sortOrder,
                startupCommand = it.startupCommand,
                startupArgs = it.startupArgs,
            )
        }
        return favoritesJson.encodeToString(FavoritesExport(favorites = entries))
    }

    suspend fun importFavoritesJson(json: String) {
        val export = runCatching { favoritesJson.decodeFromString<FavoritesExport>(json) }.getOrNull() ?: return
        export.favorites.forEach {
            wsService.addFavorite(
                name = it.name,
                path = it.path,
                sortOrder = it.sortOrder,
                startupCommand = it.startupCommand,
                startupArgs = it.startupArgs,
            )
        }
    }

    // ── Session Tags ──────────────────────────────────────────────────────────

    fun setTagFilter(tagId: String?) {
        _uiState.update { it.copy(activeTagFilter = tagId) }
    }

    fun addTag(name: String, colorHex: String) {
        if (name.isBlank()) return
        wsService.addTag(name.trim(), colorHex)
    }

    fun deleteTag(tag: SessionTag) {
        wsService.deleteTag(tag.id)
    }

    fun assignTag(sessionName: String, tagId: String) {
        wsService.assignTagToSession(sessionName, tagId)
    }

    fun removeTagFromSession(sessionName: String, tagId: String) {
        wsService.removeTagFromSession(sessionName, tagId)
    }

    fun createSessionFromFavorite(
        favorite: FavoriteSession,
        launchTarget: FavoriteLaunchTarget = FavoriteLaunchTarget.TMUX,
    ) {
        _uiState.update { it.copy(isLoading = true) }
        when (launchTarget) {
            FavoriteLaunchTarget.TMUX -> {
                wsService.createSession(
                    name = favorite.name,
                    startDirectory = favorite.path,
                    startupCommand = favorite.startupCommand,
                    startupArgs = favorite.startupArgs,
                )
                val tagIds = _uiState.value.favoriteTagMap[favorite.id] ?: emptyList()
                tagIds.forEach { tagId -> wsService.assignTagToSession(favorite.name, tagId) }
                scheduleSessionListRefresh(delayMs = 350)
            }

            FavoriteLaunchTarget.ACP -> {
                wsService.acpCreateSession(favorite.path, backend = "opencode")
            }

            FavoriteLaunchTarget.DIRECT -> {
                wsService.acpCreateSession(favorite.path, backend = "codex")
            }
        }
    }
}
