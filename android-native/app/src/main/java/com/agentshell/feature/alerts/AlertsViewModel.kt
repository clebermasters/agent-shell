package com.agentshell.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.AppNotification
import com.agentshell.data.model.NotificationFile
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsUiState(
    val notifications: List<AppNotification> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val wsService: WebSocketService,
) : ViewModel() {

    private val _state = MutableStateFlow(AlertsUiState())
    val state: StateFlow<AlertsUiState> = _state.asStateFlow()

    init {
        observeMessages()
        fetchNotifications()
    }

    fun fetchNotifications() {
        _state.update { it.copy(isLoading = true) }
        wsService.send(mapOf("type" to "list-notifications"))
    }

    fun markAsRead(id: String) {
        wsService.send(mapOf("type" to "mark-notification-read", "id" to id))
        _state.update { s ->
            val updated = s.notifications.map { n ->
                if (n.id == id) n.copy(read = true) else n
            }
            s.copy(notifications = updated, unreadCount = updated.count { !it.read })
        }
    }

    fun markAllRead() {
        wsService.send(mapOf("type" to "mark-all-notifications-read"))
        _state.update { s ->
            val updated = s.notifications.map { it.copy(read = true) }
            s.copy(notifications = updated, unreadCount = 0)
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages
                .filter { it["type"] in listOf("notifications-list", "notification-event") }
                .collect { msg ->
                    when (msg["type"] as? String) {
                        "notifications-list" -> handleList(msg)
                        "notification-event" -> handleEvent(msg)
                    }
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleList(msg: Map<String, Any?>) {
        val rawList = msg["notifications"] as? List<*> ?: return
        val notifications = rawList.mapNotNull { parseNotification(it as? Map<String, Any?>) }
            .sortedByDescending { it.timestamp }
        _state.update { it.copy(
            notifications = notifications,
            unreadCount = notifications.count { n -> !n.read },
            isLoading = false,
        )}
    }

    private fun handleEvent(msg: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val notification = parseNotification(msg["notification"] as? Map<String, Any?>) ?: return
        _state.update { s ->
            val updated = listOf(notification) + s.notifications.filterNot { it.id == notification.id }
            s.copy(notifications = updated, unreadCount = updated.count { !it.read })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNotification(map: Map<String, Any?>?): AppNotification? {
        val m = map ?: return null
        val id = m["id"] as? String ?: return null
        val title = m["title"] as? String ?: return null
        val body = m["body"] as? String ?: ""
        val source = m["source"] as? String ?: ""
        val timestamp = (m["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val read = m["read"] as? Boolean ?: false
        val rawFiles = m["files"] as? List<*>
        val files = rawFiles?.mapNotNull { parseFile(it as? Map<String, Any?>) }
        return AppNotification(
            id = id,
            title = title,
            body = body,
            source = source,
            sourceDetail = m["sourceDetail"] as? String,
            timestamp = timestamp,
            read = read,
            fileCount = (m["fileCount"] as? Number)?.toInt() ?: files?.size ?: 0,
            files = files,
        )
    }

    private fun parseFile(map: Map<String, Any?>?): NotificationFile? {
        val m = map ?: return null
        return NotificationFile(
            id = m["id"] as? String ?: return null,
            filename = m["filename"] as? String ?: return null,
            mimeType = m["mimeType"] as? String ?: "application/octet-stream",
            size = (m["size"] as? Number)?.toLong() ?: 0L,
        )
    }
}
