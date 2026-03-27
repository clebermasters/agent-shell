package com.agentshell.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.model.AppNotification
import com.agentshell.data.model.NotificationFile
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
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
    private val httpClient: OkHttpClient,
    private val hostRepository: HostRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlertsUiState())
    val state: StateFlow<AlertsUiState> = _state.asStateFlow()

    init {
        observeMessages()
        fetchNotifications()
    }

    fun fetchNotifications() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val notifications = fetchNotificationsFromApi()
            if (notifications != null) {
                val sorted = notifications.sortedByDescending { it.timestamp }
                _state.update {
                    it.copy(
                        notifications = sorted,
                        unreadCount = sorted.count { n -> !n.read },
                        isLoading = false,
                        error = null,
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false, error = "Failed to load notifications") }
            }
        }
    }

    fun markAsRead(id: String) {
        _state.update { s ->
            val updated = s.notifications.map { n -> if (n.id == id) n.copy(read = true) else n }
            s.copy(notifications = updated, unreadCount = updated.count { !it.read })
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val host = hostRepository.getSelectedHostOnce() ?: return@withContext
                    val request = Request.Builder()
                        .url("${host.httpUrl}/api/notifications/$id/read")
                        .post(RequestBody.create(null, ByteArray(0)))
                        .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (_: Exception) {}
            }
        }
    }

    fun markAllRead() {
        val unread = _state.value.notifications.filter { !it.read }
        _state.update { s ->
            val updated = s.notifications.map { it.copy(read = true) }
            s.copy(notifications = updated, unreadCount = 0)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val host = hostRepository.getSelectedHostOnce() ?: return@withContext
                unread.forEach { n ->
                    try {
                        val request = Request.Builder()
                            .url("${host.httpUrl}/api/notifications/${n.id}/read")
                            .post(RequestBody.create(null, ByteArray(0)))
                            .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                            .build()
                        httpClient.newCall(request).execute().close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun deleteNotification(id: String) {
        _state.update { s ->
            val updated = s.notifications.filterNot { it.id == id }
            s.copy(notifications = updated, unreadCount = updated.count { !it.read })
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val host = hostRepository.getSelectedHostOnce() ?: return@withContext
                    val request = Request.Builder()
                        .url("${host.httpUrl}/api/notifications/$id")
                        .delete()
                        .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (_: Exception) {}
            }
        }
    }

    fun deleteAllNotifications() {
        _state.update { AlertsUiState() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val host = hostRepository.getSelectedHostOnce() ?: return@withContext
                    val request = Request.Builder()
                        .url("${host.httpUrl}/api/notifications")
                        .delete()
                        .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (_: Exception) {}
            }
        }
    }

    // Listen for real-time notification events broadcast by the backend over WebSocket
    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages
                .filter { it["type"] == "notification-event" }
                .collect { msg -> handleEvent(msg) }
        }
    }

    private fun handleEvent(msg: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val data = msg["notification"] as? Map<String, Any?> ?: return
        val notification = parseNotificationMap(data) ?: return
        _state.update { s ->
            val updated = listOf(notification) + s.notifications.filterNot { it.id == notification.id }
            s.copy(notifications = updated, unreadCount = updated.count { !it.read })
        }
    }

    // -------------------------------------------------------------------------
    // REST API helpers
    // -------------------------------------------------------------------------

    private suspend fun fetchNotificationsFromApi(): List<AppNotification>? = withContext(Dispatchers.IO) {
        try {
            val host = hostRepository.getSelectedHostOnce() ?: return@withContext null
            val url = "${host.httpUrl}/api/notifications?limit=50"
            val request = Request.Builder().url(url).get()
                .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return@withContext null }
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val arr = json.getJSONArray("notifications")
            (0 until arr.length()).mapNotNull { i -> parseNotificationJson(arr.getJSONObject(i)) }
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Parsers — JSON (REST response) and Map (WebSocket event)
    // -------------------------------------------------------------------------

    private fun parseNotificationJson(obj: JSONObject): AppNotification? {
        val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: return null
        val title = obj.optString("title", "").takeIf { it.isNotEmpty() } ?: return null
        val filesArr = obj.optJSONArray("files")
        val files = filesArr?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val f = arr.getJSONObject(i)
                NotificationFile(
                    id = f.optString("id", ""),
                    filename = f.optString("filename", ""),
                    mimeType = f.optString("mimeType", "application/octet-stream"),
                    size = f.optLong("size", 0L),
                )
            }
        }
        return AppNotification(
            id = id,
            title = title,
            body = obj.optString("body", ""),
            source = obj.optString("source", ""),
            sourceDetail = obj.optString("sourceDetail", null),
            timestamp = obj.optLong("timestampMillis", System.currentTimeMillis()),
            read = obj.optBoolean("read", false),
            fileCount = obj.optInt("fileCount", files?.size ?: 0),
            files = files,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNotificationMap(map: Map<String, Any?>): AppNotification? {
        val id = map["id"] as? String ?: return null
        val title = map["title"] as? String ?: return null
        val rawFiles = map["files"] as? List<*>
        val files = rawFiles?.mapNotNull { parseFileMap(it as? Map<String, Any?>) }
        return AppNotification(
            id = id,
            title = title,
            body = map["body"] as? String ?: "",
            source = map["source"] as? String ?: "",
            sourceDetail = map["sourceDetail"] as? String,
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            read = map["read"] as? Boolean ?: false,
            fileCount = (map["fileCount"] as? Number)?.toInt() ?: files?.size ?: 0,
            files = files,
        )
    }

    private fun parseFileMap(map: Map<String, Any?>?): NotificationFile? {
        val m = map ?: return null
        return NotificationFile(
            id = m["id"] as? String ?: return null,
            filename = m["filename"] as? String ?: return null,
            mimeType = m["mimeType"] as? String ?: "application/octet-stream",
            size = (m["size"] as? Number)?.toLong() ?: 0L,
        )
    }

    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val host = hostRepository.getSelectedHostOnce() ?: return@withContext null
            val url = "${host.httpUrl}/api/notifications/files/$fileId"
            val request = Request.Builder().url(url).get()
                .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                response.close()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
