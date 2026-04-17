package com.agentshell.feature.splitscreen

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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val MAX_PANEL_PENDING_MESSAGES = 32

class SplitPanelSocket(
    baseClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val pendingQueueLock = Any()
    private val pendingQueue = ArrayDeque<String>()

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var connectJob: Job? = null
    private var reconnectAttempts = 0
    private var intentionalClose = false
    private var listenerId = 0L

    private val _messages = MutableSharedFlow<Map<String, Any?>>(replay = 0, extraBufferCapacity = 128)
    val messages: SharedFlow<Map<String, Any?>> = _messages.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun connect(url: String) {
        if (url == currentUrl && (_isConnected.value || connectJob?.isActive == true)) {
            return
        }

        currentUrl = url
        reconnectAttempts = 0
        intentionalClose = false

        connectJob?.cancel()
        webSocket?.cancel()
        _isConnected.value = false
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
            while (pendingQueue.size > MAX_PANEL_PENDING_MESSAGES) {
                pendingQueue.removeFirstOrNull()
            }
        }
    }

    fun dispose() {
        intentionalClose = true
        connectJob?.cancel()
        webSocket?.close(1000, "Split panel disposed")
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
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale(): Boolean = activeId != listenerId

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStale()) return
                _isConnected.value = true
                reconnectAttempts = 0
                flushPendingQueue(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStale()) return
                runCatching {
                    jsonObjectToMap(JSONObject(text))
                }.onSuccess { message ->
                    scope.launch { _messages.emit(message) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStale() || intentionalClose) return
                handleUnexpectedDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale() || intentionalClose) return
                handleUnexpectedDisconnect()
            }
        })
    }

    private fun handleUnexpectedDisconnect() {
        _isConnected.value = false
        reconnectAttempts += 1
        connectJob?.cancel()
        connectJob = scope.launch {
            val delayMs = (1_000L * (1L shl reconnectAttempts.coerceAtMost(3))).coerceAtMost(10_000L)
            delay(delayMs)
            doConnect()
        }
    }

    private fun flushPendingQueue(webSocket: WebSocket) {
        val pending = synchronized(pendingQueueLock) {
            val snapshot = pendingQueue.toList()
            pendingQueue.clear()
            snapshot
        }
        pending.forEach(webSocket::send)
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
