package com.agentshell.data.remote

import com.agentshell.core.diagnostics.BatteryDiagnostics
import com.agentshell.core.diagnostics.DiagnosticSocketRole
import com.agentshell.core.power.ConnectionNetworkGate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.collect
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

class SessionSocket internal constructor(
    baseClient: OkHttpClient,
    private val diagnosticRole: DiagnosticSocketRole,
    private val networkAllowed: StateFlow<Boolean>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(baseClient: OkHttpClient) : this(baseClient, DiagnosticSocketRole.SESSION, ConnectionNetworkGate.allowed)
    constructor(baseClient: OkHttpClient, diagnosticRole: DiagnosticSocketRole = DiagnosticSocketRole.SESSION) : this(baseClient, diagnosticRole, ConnectionNetworkGate.allowed)

    private val lifecycleLock = Any()
    private val diagnosticToken = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
    @Volatile private var connectionDesired = false
    @Volatile private var isConnecting = false
    @Volatile private var disposed = false
    @Volatile private var listenerId = 0L
    private var incoming: OrderedSocketMessages<Map<String, Any?>>? = null
    private val heartbeat = WebSocketHeartbeat(scope) { connection ->
        if (connection == listenerId && _isConnected.value && mayConnect()) {
            BatteryDiagnostics.event("heartbeat_timeout", diagnosticRole)
            forceReconnect()
        }
    }

    private val _messages = MutableSharedFlow<Map<String, Any?>>(replay = 0, extraBufferCapacity = 128)
    val messages: SharedFlow<Map<String, Any?>> = _messages.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _connectionGeneration = MutableStateFlow(0L)
    val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            networkAllowed.collect {
                synchronized(lifecycleLock) {
                    if (!networkAllowed.value) pauseTransportLocked()
                    else if (mayConnect()) doConnect()
                }
            }
        }
    }

    private fun mayConnect() = !disposed && connectionDesired && networkAllowed.value

    private fun pauseTransportLocked() {
        intentionalClose = true
        connectJob?.cancel(); connectJob = null
        heartbeat.cancel()
        listenerId++
        incoming?.cancel(); incoming = null
        val old = webSocket
        webSocket = null
        isConnecting = false
        _isConnected.value = false
        BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
        synchronized(pendingQueueLock) { pendingQueue.clear() }
        old?.cancel()
    }

    fun connect(url: String) {
        synchronized(lifecycleLock) {
            if (disposed) return
            connectionDesired = true
            if (url == currentUrl && mayConnect() && (_isConnected.value || isConnecting || connectJob?.isActive == true)) {
                return
            }

            if (currentUrl != null && currentUrl != url) synchronized(pendingQueueLock) { pendingQueue.clear() }
            currentUrl = url
            if (!mayConnect()) { pauseTransportLocked(); return }
            BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
            reconnectAttempts = 0
            intentionalClose = false

            connectJob?.cancel()
            heartbeat.cancel()
            listenerId++
            incoming?.cancel()
            webSocket?.cancel()
            _isConnected.value = false
            isConnecting = false
            doConnect()
        }
    }

    /** Bound submissions must never be replayed after a link or server changes. */
    fun sendIfConnected(message: Map<String, Any?>, expectedServerUrl: String): Boolean {
        synchronized(lifecycleLock) {
            val socket = webSocket ?: return false
            if (!mayConnect() || !_isConnected.value || currentUrl != expectedServerUrl) return false
            val payload = mapToJson(message).toString()
            if (!_isConnected.value || currentUrl != expectedServerUrl || webSocket !== socket) return false
            return sendObserved(socket, payload, message["type"] as? String)
        }
    }

    fun checkConnection() {
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
            val socket = webSocket
            if (_isConnected.value && socket != null) {
                val connection = listenerId
                heartbeat.ping(connection, 10_000) {
                    _isConnected.value && listenerId == connection && webSocket === socket && sendObserved(socket, "{\"type\":\"ping\"}", "ping")
                }
            } else if (currentUrl != null) forceReconnect()
        }
    }

    fun forceReconnect() {
        synchronized(lifecycleLock) {
            if (currentUrl == null || !mayConnect()) return
            BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
            intentionalClose = false
            connectJob?.cancel()
            heartbeat.cancel()
            listenerId++
            incoming?.cancel()
            webSocket?.cancel()
            webSocket = null
            _isConnected.value = false
            isConnecting = false
            reconnectAttempts = 0
            doConnect()
        }
    }

    fun send(message: Map<String, Any?>) {
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
            val payload = mapToJson(message).toString()
            val ws = webSocket
            if (_isConnected.value && ws != null) {
                sendObserved(ws, payload, message["type"] as? String)
                return
            }

            synchronized(pendingQueueLock) {
                pendingQueue.addLast(payload)
                while (pendingQueue.size > MAX_SESSION_PENDING_MESSAGES) {
                    pendingQueue.removeFirstOrNull()
                }
            }
        }
    }

    fun dispose() {
        synchronized(lifecycleLock) {
            connectionDesired = false
            disposed = true
            BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
            intentionalClose = true
            connectJob?.cancel()
            heartbeat.cancel()
            listenerId++
            incoming?.cancel()
            webSocket?.close(1000, "Session socket disposed")
            webSocket = null
            _isConnected.value = false
            isConnecting = false
            synchronized(pendingQueueLock) {
                pendingQueue.clear()
            }
            scope.cancel()
        }
    }

    private fun doConnect() {
        synchronized(lifecycleLock) {
            if (!mayConnect() || _isConnected.value || isConnecting) return
            val url = currentUrl ?: return
            intentionalClose = false
            isConnecting = true
            val request = Request.Builder().url(url).build()
            val activeId = ++listenerId
            incoming?.cancel()
            val packets = OrderedSocketMessages<Map<String, Any?>>(scope, 128) { message ->
                if (activeId == listenerId && mayConnect()) _messages.emit(message)
            }
            incoming = packets
            BatteryDiagnostics.event("socket_attempt", diagnosticRole)
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                private fun isStale(): Boolean = activeId != listenerId || !mayConnect()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(lifecycleLock) {
                        if (isStale()) { webSocket.cancel(); return }
                        BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, true)
                        BatteryDiagnostics.event("socket_open", diagnosticRole)
                        isConnecting = false
                        reconnectAttempts = 0
                        _connectionGeneration.update { it + 1 }
                        _isConnected.value = true
                        flushPendingQueue(webSocket, activeId)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isStale()) return
                    runCatching {
                        val json = JSONObject(text)
                        val message = jsonObjectToMap(json)
                        BatteryDiagnostics.message(diagnosticRole, true, text, json.optString("type"))
                        message
                    }.onFailure {
                        BatteryDiagnostics.message(diagnosticRole, true, text)
                    }.onSuccess { message ->
                        if (message["type"] == "pong") heartbeat.pong(activeId)
                        packets.accept(message)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    packets.close()
                    synchronized(lifecycleLock) {
                        if (isStale() || intentionalClose) return
                        BatteryDiagnostics.event("socket_failed", diagnosticRole)
                        handleUnexpectedDisconnect()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (isStale()) { webSocket.cancel(); return }
                    packets.close()
                    webSocket.close(if (code == 1005) 1000 else code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    packets.close()
                    synchronized(lifecycleLock) {
                        if (isStale() || intentionalClose) return
                        BatteryDiagnostics.event("socket_closed", diagnosticRole)
                        handleUnexpectedDisconnect()
                    }
                }
            })
        }
    }

    private fun handleUnexpectedDisconnect() {
        if (!mayConnect()) return
        BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
        BatteryDiagnostics.event("reconnect_scheduled", diagnosticRole)
        _isConnected.value = false
        isConnecting = false
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
            if (!mayConnect() || activeId != listenerId || this.webSocket !== webSocket) return
            sendObserved(webSocket, payload)
        }
    }

    private fun sendObserved(socket: WebSocket, text: String, type: String? = null): Boolean {
        if (!mayConnect()) return false
        val accepted = socket.send(text)
        if (accepted) BatteryDiagnostics.message(diagnosticRole, false, text, type)
        return accepted
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
