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
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _newAcpSession = MutableSharedFlow<NewAcpSessionEvent>(extraBufferCapacity = 8)
    val newAcpSession: SharedFlow<NewAcpSessionEvent> = _newAcpSession.asSharedFlow()
    private val _sessionCwdResults = MutableSharedFlow<SessionCwdResult>(extraBufferCapacity = 8)
    private var pendingTagSnapshot = PendingTagSnapshot()
    private val sessionCwdCache = linkedMapOf<String, String>()

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
                                tool = s["tool"] as? String,
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
                                provider = s["provider"] as? String,
                            )
                        }
                        _uiState.update { it.copy(acpSessions = sessions, isLoading = false) }
                    }

                    "acp-session-created" -> {
                        val sessionId = message["sessionId"] as? String
                        val cwd = message["cwd"] as? String
                        if (sessionId != null && cwd != null) {
                            _uiState.update { it.copy(error = null) }
                            viewModelScope.launch {
                                delay(300)
                                requestSessions()
                            }
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
                        viewModelScope.launch {
                            delay(500)
                            requestSessions()
                        }
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
                        viewModelScope.launch {
                            favoriteSessionDao.deleteAll()
                            favoriteSessionDao.insertAll(favorites)
                        }
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
        wsService.getFavorites()
        wsService.getTags()
        wsService.getTagAssignments()
    }

    fun createSession(name: String) {
        _uiState.update { it.copy(isLoading = true) }
        wsService.createSession(name)
        viewModelScope.launch {
            delay(500)
            requestSessions()
        }
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
                viewModelScope.launch {
                    delay(500)
                    requestSessions()
                }
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
