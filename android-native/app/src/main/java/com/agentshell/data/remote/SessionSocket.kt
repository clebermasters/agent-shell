package com.agentshell.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val MAX_SESSION_PENDING_MESSAGES = 32

class SessionSocket(
    baseClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val pendingQueueLock = Any()
    private val pendingQueue = ArrayDeque<String>()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentUrl: String? = null
    private var connectJob: Job? = null
    private var reconnectAttempts = 0
    private var intentionalClose = false
    @Volatile private var listenerId = 0L
    private var incoming: OrderedSocketMessages<Map<String, Any?>>? = null
    private val heartbeat = WebSocketHeartbeat(scope) { connection ->
        if (connection == listenerId && _isConnected.value) forceReconnect()
    }

    private val _messages = MutableSharedFlow<Map<String, Any?>>(replay = 0, extraBufferCapacity = 128)
    val messages: SharedFlow<Map<String, Any?>> = _messages.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _connectionGeneration = MutableStateFlow(0L)
    val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    fun connect(url: String) {
        if (url == currentUrl && (_isConnected.value || connectJob?.isActive == true)) {
            return
        }

        if (currentUrl != null && currentUrl != url) synchronized(pendingQueueLock) { pendingQueue.clear() }
        currentUrl = url
        reconnectAttempts = 0
        intentionalClose = false

        connectJob?.cancel()
        heartbeat.cancel()
        listenerId++
        incoming?.cancel()
        webSocket?.cancel()
        _isConnected.value = false
        doConnect()
    }

    /** Bound submissions must never be replayed after a link or server changes. */
    fun sendIfConnected(message: Map<String, Any?>, expectedServerUrl: String): Boolean {
        val socket = webSocket ?: return false
        if (!_isConnected.value || currentUrl != expectedServerUrl) return false
        val payload = mapToJson(message).toString()
        if (!_isConnected.value || currentUrl != expectedServerUrl || webSocket !== socket) return false
        return socket.send(payload)
    }

    fun checkConnection() {
        val socket = webSocket
        if (_isConnected.value && socket != null) {
            val connection = listenerId
            heartbeat.ping(connection, 10_000) {
                _isConnected.value && listenerId == connection && webSocket === socket && socket.send("{\"type\":\"ping\"}")
            }
        } else if (currentUrl != null) forceReconnect()
    }

    fun forceReconnect() {
        if (currentUrl == null) return
        intentionalClose = false
        connectJob?.cancel()
        heartbeat.cancel()
        listenerId++
        incoming?.cancel()
        webSocket?.cancel()
        webSocket = null
        _isConnected.value = false
        reconnectAttempts = 0
        doConnect()
    }

    fun send(message: Map<String, Any?>) {
        val payload = mapToJson(message).toString()
        val ws = webSocket
        if (_isConnected.value && ws != null) {
            ws.send(payload)
            return
        }

        synchronized(pendingQueueLock) {
            pendingQueue.addLast(payload)
            while (pendingQueue.size > MAX_SESSION_PENDING_MESSAGES) {
                pendingQueue.removeFirstOrNull()
            }
        }
    }

    fun dispose() {
        intentionalClose = true
        connectJob?.cancel()
        heartbeat.cancel()
        listenerId++
        incoming?.cancel()
        webSocket?.close(1000, "Session socket disposed")
        webSocket = null
        _isConnected.value = false
        synchronized(pendingQueueLock) {
            pendingQueue.clear()
        }
        scope.cancel()
    }

    private fun doConnect() {
        val url = currentUrl ?: return
        val request = Request.Builder().url(url).build()
        val activeId = ++listenerId
        incoming?.cancel()
        val packets = OrderedSocketMessages<Map<String, Any?>>(scope, 128) { message ->
            if (activeId == listenerId) _messages.emit(message)
        }
        incoming = packets
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale(): Boolean = activeId != listenerId

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStale()) return
                _isConnected.value = true
                reconnectAttempts = 0
                _connectionGeneration.update { it + 1 }
                flushPendingQueue(webSocket, activeId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStale()) return
                runCatching {
                    jsonObjectToMap(JSONObject(text))
                }.onSuccess { message ->
                    if (message["type"] == "pong") heartbeat.pong(activeId)
                    packets.accept(message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                packets.close()
                if (isStale() || intentionalClose) return
                handleUnexpectedDisconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                packets.close()
                webSocket.close(if (code == 1005) 1000 else code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                packets.close()
                if (isStale() || intentionalClose) return
                handleUnexpectedDisconnect()
            }
        })
    }

    private fun handleUnexpectedDisconnect() {
        _isConnected.value = false
        heartbeat.cancel()
        reconnectAttempts += 1
        connectJob?.cancel()
        connectJob = scope.launch {
            val delayMs = (1_000L * (1L shl reconnectAttempts.coerceAtMost(3))).coerceAtMost(10_000L)
            delay(delayMs)
            doConnect()
        }
    }

    private fun flushPendingQueue(webSocket: WebSocket, activeId: Long) {
        val pending = synchronized(pendingQueueLock) {
            val snapshot = pendingQueue.toList()
            pendingQueue.clear()
            snapshot
        }
        for (payload in pending) {
            if (activeId != listenerId || this.webSocket !== webSocket) return
            webSocket.send(payload)
        }
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            when (value) {
                null -> obj.put(key, JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    obj.put(key, mapToJson(value as Map<String, Any?>))
                }
                is List<*> -> obj.put(key, listToJsonArray(value))
                else -> obj.put(key, value)
            }
        }
        return obj
    }

    private fun listToJsonArray(list: List<*>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            when (item) {
                null -> arr.put(JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    arr.put(mapToJson(item as Map<String, Any?>))
                }
                is List<*> -> arr.put(listToJsonArray(item))
                else -> arr.put(item)
            }
        }
        return arr
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = jsonValueToKotlin(obj.get(key))
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (index in 0 until arr.length()) {
            list.add(jsonValueToKotlin(arr.get(index)))
        }
        return list
    }

    private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}
