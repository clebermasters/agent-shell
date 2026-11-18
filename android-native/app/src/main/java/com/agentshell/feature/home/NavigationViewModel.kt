package com.agentshell.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore,
    private val hostRepository: HostRepository,
    private val webSocketService: WebSocketService,
) : ViewModel() {
    val keepScreenOn: StateFlow<Boolean> = preferencesDataStore.keepScreenOn.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    internal val chatConnectionGeneration = webSocketService.connectionGeneration

    private suspend fun catalogReader(hostId: String): DeploymentCatalogReader? {
        val host = hostRepository.getSelectedHostOnce()?.takeIf { it.id == hostId } ?: return null
        val source = "${host.wsUrl}/ws"
        val socketUrl = webSocketService.currentWebSocketUrl ?: return null
        if (socketUrl.substringBefore('?') != source) return null
        return DeploymentCatalogReader(hostId, source, socketUrl, webSocketService)
    }

    internal suspend fun findDeploymentChat(target: ChatTarget): ChatTarget? {
        val catalog = catalogReader(target.hostId) ?: return null
        val sameSession = if (target.isAcp) catalog.directChats() else catalog.windows(target.sessionName)
        return sameSession?.firstOrNull { it.key == target.key }
    }

    /** Query the current server without opening a second transport or changing its active chat. */
    internal suspend fun deploymentChatChoices(hostId: String): DeploymentChatChoices = coroutineScope {
        val catalog = catalogReader(hostId) ?: return@coroutineScope DeploymentChatChoices(incomplete = true)
        val direct = async { catalog.directChats() }
        val sessions = catalog.sessions()
        val windows = sessions.orEmpty().map { name -> async { catalog.windows(name) } }.awaitAll()
        val directSessions = direct.await()
        val chats = windows.filterNotNull().flatten() + directSessions.orEmpty()
        val recent = preferencesDataStore.recentChatSessions(hostId).first().firstOrNull()
        val lastVisited = chats.firstOrNull {
            recent == if (it.isAcp) "acp:${it.sessionName}" else "tmux:${it.sessionName}\u0000${it.windowIndex}"
        }
        DeploymentChatChoices(chats, lastVisited, sessions == null || directSessions == null || windows.any { it == null })
    }

    suspend fun prepareChatNotification(target: ChatTarget): Boolean {
        val host = hostRepository.getHosts().first().firstOrNull { it.id == target.hostId } ?: return false
        hostRepository.selectHost(host.id)
        // Set the correct connection before the destination queues its chat watch.
        webSocketService.connect("${host.wsUrl}/ws", BuildConfig.AUTH_TOKEN.ifEmpty { null })
        return true
    }
}

internal data class DeploymentChatChoices(
    val chats: List<ChatTarget> = emptyList(),
    val lastVisited: ChatTarget? = null,
    val incomplete: Boolean = false,
)

private class DeploymentCatalogReader(
    private val hostId: String,
    private val source: String,
    private val socketUrl: String,
    private val socket: WebSocketService,
) {
    suspend fun sessions() = rows("list-sessions", "sessions-list", "sessions")
        ?.mapNotNull { it["name"] as? String }?.distinct()

    suspend fun directChats() = rows("acp-list-sessions", "acp-sessions-listed", "sessions")?.mapNotNull { row ->
        val name = row["sessionId"] as? String ?: return@mapNotNull null
        ChatTarget.create(hostId, name, isAcp = true, cwd = row["cwd"] as? String ?: "", title = row["title"] as? String ?: "")
    }

    suspend fun windows(name: String) = rows("list-windows", "windows-list", "windows", name)?.mapNotNull { window ->
        val index = (window["index"] as? Number)?.toInt() ?: return@mapNotNull null
        ChatTarget.create(hostId, name, index, title = window["name"] as? String ?: "")
    }

    private suspend fun rows(request: String, reply: String, field: String, session: String? = null): List<Map<*, *>>? {
        val command = buildMap<String, Any?> {
            put("type", request)
            if (session != null) put("sessionName", session)
        }
        if (!socket.isConnected) return null
        val response = withTimeoutOrNull(6_000L) {
            socket.messages.onSubscription {
                socket.sendIfConnected(command, socketUrl)
            }.first {
                it["_sourceServerUrl"] == source &&
                    (it["type"] == reply || (reply == "sessions-list" && it["type"] == "session_list")) &&
                    (session == null || it["sessionName"] == session)
            }
        }
        return (response?.get(field) as? List<*>)?.filterIsInstance<Map<*, *>>()
    }
}
