package com.agentshell.data.remote

import com.agentshell.data.model.ConnectionStatus
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
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketService @Inject constructor(
    baseClient: OkHttpClient,
) {
    private data class PendingMessage(
        val type: String?,
        val payload: String,
    )


    // -------------------------------------------------------------------------
    // Coroutine scope – SupervisorJob so one child failure doesn't cancel others
    // -------------------------------------------------------------------------
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // OkHttp client – derive from the injected base client but override
    // readTimeout to 0 (infinite) as required for WebSocket connections.
    // -------------------------------------------------------------------------
    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var activeListenerId = 0L  // Monotonic ID to ignore stale listener callbacks
    private var activeListener: Listener? = null

    val currentWebSocketUrl: String? get() = currentUrl

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    @Volatile
    private var _isConnecting = false  // Guards against duplicate connect() calls

    private var reconnectAttempts = 0
    private var intentionalClose = false

    // Pending messages buffered while disconnected
    private val pendingQueue = ArrayDeque<PendingMessage>()
    private val pendingQueueLock = Any()

    // Jobs for ping / pong-timeout / reconnect timers
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val heartbeat = WebSocketHeartbeat(scope) { connection ->
        if (connection == activeListenerId && _isConnected) {
            println("[CONN] Android→Backend PONG TIMEOUT — forcing reconnect")
            forceReconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Public flows
    // -------------------------------------------------------------------------
    private val _messages = MutableSharedFlow<Map<String, Any?>>(replay = 0, extraBufferCapacity = 256)
    val messages: SharedFlow<Map<String, Any?>> = _messages.asSharedFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // A fast reconnect can conflate the status enum back to CONNECTED before
    // observers run. This durable generation still requires fresh chat watches.
    private val _connectionGeneration = MutableStateFlow(0L)
    val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    private val _keepAliveEnabled = MutableStateFlow(false)
    val keepAliveEnabled: StateFlow<Boolean> = _keepAliveEnabled.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------
    private fun log(message: String) {
        val timestamp = Instant.now().toString()
        scope.launch { _logs.emit("[$timestamp] $message") }
    }

    private fun sanitizeUrl(url: String): String =
        url.replace(Regex("[?&]token=[^&]*"), "")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Connect to a WebSocket URL, appending [authToken] as a query parameter
     * when provided (mirrors Flutter's connect() behaviour).
     */
    fun connect(url: String, authToken: String? = null) {
        var finalUrl = url
        if (authToken != null && authToken.isNotEmpty()) {
            val separator = if (finalUrl.contains('?')) "&" else "?"
            finalUrl = "$finalUrl${separator}token=${android.net.Uri.encode(authToken)}"
        }
        _keepAliveEnabled.value = true
        // Skip if already connected or connecting to the same URL
        if (finalUrl == currentUrl && (_isConnected || _isConnecting)) {
            log("Already connected/connecting to ${sanitizeUrl(finalUrl)}, skipping")
            return
        }
        // Disconnect from previous host if switching
        if (currentUrl != null && currentUrl != finalUrl) {
            log("Switching host — resetting previous websocket")
            intentionalClose = true
            activeListenerId++
            activeListener?.retire()
            try {
                webSocket?.cancel()
            } catch (_: Exception) {}
            webSocket = null
            _isConnected = false
            _isConnecting = false
            cancelTimers()
            // Buffered terminal input and mutations belong to the previous server only.
            synchronized(pendingQueueLock) { pendingQueue.clear() }
        }
        currentUrl = finalUrl
        reconnectAttempts = 0
        intentionalClose = false
        log("Connecting to: ${sanitizeUrl(finalUrl)}")
        doConnect()
    }

    /** Close the WebSocket cleanly; does NOT schedule a reconnect. */
    fun disconnect() {
        log("Disconnecting...")
        intentionalClose = true
        _keepAliveEnabled.value = false
        cancelTimers()
        activeListenerId++
        activeListener?.retire()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _isConnected = false
        _isConnecting = false
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.OFFLINE
    }

    /**
     * Cancel any pending reconnect, close the current socket immediately,
     * and reconnect from scratch (mirrors Flutter's forceReconnect()).
     */
    fun forceReconnect() {
        if (currentUrl == null) return
        log("Forcing immediate reconnect...")
        intentionalClose = false
        _keepAliveEnabled.value = true
        cancelTimers()
        activeListenerId++
        activeListener?.retire()
        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
        _isConnected = false
        _isConnecting = false
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        doConnect()
    }

    /**
     * Send a message map. If connected, serialises and sends immediately.
     * Otherwise queues it and triggers a reconnect.
     */
    fun send(message: Map<String, Any?>) {
        val json = mapToJson(message).toString()
        if (_isConnected && webSocket != null) {
            webSocket!!.send(json)
        } else {
            enqueuePendingMessage(message["type"] as? String, json)
            // Only trigger reconnect if not already connecting/reconnecting
            if (!_isConnecting && (reconnectJob == null || reconnectJob!!.isCompleted)) {
                scheduleReconnect()
            }
        }
    }

    /** Send a transfer only to its original server; never queue it for a later connection. */
    fun sendIfConnected(message: Map<String, Any?>, expectedServerUrl: String): Boolean {
        val socket = webSocket ?: return false
        if (!_isConnected || currentUrl != expectedServerUrl) return false
        val json = mapToJson(message).toString()
        if (!_isConnected || currentUrl != expectedServerUrl || webSocket !== socket) return false
        return socket.send(json)
    }

    /**
     * Check connection health: if disconnected, force reconnect immediately;
     * if connected, send a ping to verify the connection is still alive.
     * Called when the app returns to foreground.
     */
    fun checkConnection() {
        log("checkConnection status=${_connectionStatus.value} connected=$_isConnected connecting=$_isConnecting url=${sanitizeUrl(currentUrl ?: "")}")
        if (_isConnected) {
            probeConnection(10_000)
        } else if (currentUrl != null) {
            forceReconnect()
        }
    }

    fun onNetworkAvailable() {
        if (!_keepAliveEnabled.value || currentUrl == null) return
        log("Network available — checking websocket health")
        checkConnection()
    }

    /** Release all resources permanently. */
    fun dispose() {
        disconnect()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal connection management
    // -------------------------------------------------------------------------

    private fun doConnect() {
        val url = currentUrl ?: return

        // Don't open a new socket if we're already connected or mid-handshake
        if (_isConnected || _isConnecting) {
            log("doConnect() skipped — already connected/connecting")
            return
        }

        // Emit the correct status based on attempt count (mirrors Flutter exactly)
        _connectionStatus.value = when {
            reconnectAttempts == 0 -> ConnectionStatus.CONNECTING
            reconnectAttempts < 3  -> ConnectionStatus.RECONNECTING
            else                   -> ConnectionStatus.OFFLINE
        }

        _isConnecting = true
        log("Attempting WebSocket connection to: ${sanitizeUrl(url)} reconnectAttempts=$reconnectAttempts pendingQueue=${pendingQueueSize()}")

        val listenerId = ++activeListenerId
        val request = Request.Builder().url(url).build()
        activeListener?.retire()
        val listener = Listener(listenerId)
        activeListener = listener
        webSocket = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        // Don't schedule if already connected (race with successful reconnect)
        if (_isConnected || !_keepAliveEnabled.value) return
        // Exponential backoff: 2s, 4s, 8s, 16s, cap at 30s — never give up
        val delayMs = (2_000L * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        _connectionStatus.value = when {
            reconnectAttempts < 3 -> ConnectionStatus.RECONNECTING
            else -> ConnectionStatus.OFFLINE
        }
        log("Scheduling reconnect #$reconnectAttempts in ${delayMs}ms pendingQueue=${pendingQueueSize()} url=${sanitizeUrl(currentUrl ?: "")}")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            doConnect()
        }
    }

    private fun cancelTimers() {
        pingJob?.cancel();         pingJob = null
        heartbeat.cancel()
        reconnectJob?.cancel();    reconnectJob = null
    }

    private fun cancelHeartbeat() {
        pingJob?.cancel();        pingJob = null
        heartbeat.cancel()
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        _isConnecting = false
        _isConnected = false
        cancelHeartbeat()
        reconnectAttempts++
        log("$reason reconnectAttempts=$reconnectAttempts")
        scheduleReconnect()
    }

    private fun startPingTimer() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(30_000)
                if (!_isConnected) break
                probeConnection(20_000)
            }
        }
    }

    private fun probeConnection(timeoutMillis: Long) {
        val socket = webSocket ?: return
        val connection = activeListenerId
        if (!_isConnected) return
        heartbeat.ping(connection, timeoutMillis) {
            _isConnected && connection == activeListenerId && webSocket === socket && socket.send("{\"type\":\"ping\"}")
        }
    }

    private fun flushPendingQueue(socket: WebSocket, listenerId: Long) {
        val snapshot = synchronized(pendingQueueLock) {
            if (listenerId != activeListenerId || webSocket !== socket) return
            if (pendingQueue.isEmpty()) return
            val drained = pendingQueue.map(PendingMessage::payload)
            pendingQueue.clear()
            drained
        }
        log("Flushing ${snapshot.size} queued message(s) after reconnect")
        for (json in snapshot) {
            if (listenerId != activeListenerId) return
            socket.send(json)
        }
    }

    private fun enqueuePendingMessage(type: String?, payload: String) {
        synchronized(pendingQueueLock) {
            if (type in COLLAPSIBLE_PENDING_TYPES) {
                pendingQueue.removeAll { it.type == type }
            }
            pendingQueue.addLast(PendingMessage(type = type, payload = payload))
            while (pendingQueue.size > MAX_PENDING_QUEUE_SIZE) {
                pendingQueue.removeFirstOrNull()
            }
        }
    }

    private fun pendingQueueSize(): Int = synchronized(pendingQueueLock) { pendingQueue.size }

    // -------------------------------------------------------------------------
    // WebSocketListener implementation
    // -------------------------------------------------------------------------

    private inner class Listener(private val id: Long) : WebSocketListener() {
        private val sourceServerUrl = currentUrl?.substringBefore('?')
        private var serverRestartScheduled = false
        private val incoming = OrderedSocketMessages<Map<String, Any?>>(scope, 256) { message ->
            if (!isStale()) _messages.emit(message)
        }

        fun retire() = incoming.cancel()

        /** Returns true if this listener has been superseded by a newer connection. */
        private fun isStale(): Boolean = id != activeListenerId

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale()) return
            log("onOpen listenerId=$id url=${sanitizeUrl(currentUrl ?: "")} pendingQueue=${pendingQueueSize()}")
            println("[CONN] Android→Backend CONNECTED to ${sanitizeUrl(currentUrl ?: "")}")
            _isConnecting = false
            _isConnected = true
            reconnectAttempts = 0
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _connectionGeneration.update { it + 1 }
            flushPendingQueue(webSocket, id)
            startPingTimer()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale()) return
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "")

                // Cancel pong-timeout when a pong arrives
                if (type == "pong") {
                    heartbeat.pong(id)
                }

                // Handle server graceful shutdown — reconnect after the suggested delay
                if (type == "server-shutting-down") {
                    val delayMs = json.optLong("reconnectDelayMs", 2000L)
                    serverRestartScheduled = true
                    println("[CONN] Server shutting down — will reconnect in ${delayMs}ms")
                    cancelTimers()
                    _isConnected = false
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    reconnectAttempts = 0
                    reconnectJob = scope.launch {
                        delay(delayMs)
                        doConnect()
                    }
                    return
                }

                val messageMap = jsonObjectToMap(json) + ("_sourceServerUrl" to sourceServerUrl)
                incoming.accept(messageMap)
            } catch (e: Exception) {
                log("Failed to parse message: $e")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close()
            if (isStale() || intentionalClose || serverRestartScheduled) return
            log("onFailure listenerId=$id error=${t.message} reconnectAttempts=${reconnectAttempts + 1} url=${sanitizeUrl(currentUrl ?: "")}")
            println("[CONN] Android→Backend WebSocket ERROR: $t (url: ${sanitizeUrl(currentUrl ?: "")})")
            handleUnexpectedDisconnect("WebSocket failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close()
            // OkHttp delivers onClosed only after we acknowledge the peer's
            // close. An empty close frame reports reserved status 1005.
            webSocket.close(if (code == 1005) 1000 else code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close()
            if (isStale() || intentionalClose || serverRestartScheduled) return
            log("onClosed listenerId=$id code=$code reason=$reason reconnectAttempts=${reconnectAttempts + 1} url=${sanitizeUrl(currentUrl ?: "")}")
            println("[CONN] Android→Backend WebSocket CLOSED (url: ${sanitizeUrl(currentUrl ?: "")})")
            handleUnexpectedDisconnect("WebSocket closed code=$code reason=$reason")
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers (org.json – no extra deps, matches build.gradle.kts)
    // -------------------------------------------------------------------------

    /** Recursively convert a [Map] into a [JSONObject]. */
    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            when (value) {
                null         -> obj.put(key, JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    obj.put(key, mapToJson(value as Map<String, Any?>))
                }
                is List<*>   -> obj.put(key, listToJsonArray(value))
                else         -> obj.put(key, value)
            }
        }
        return obj
    }

    private fun listToJsonArray(list: List<*>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (item in list) {
            when (item) {
                null         -> arr.put(JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    arr.put(mapToJson(item as Map<String, Any?>))
                }
                is List<*>   -> arr.put(listToJsonArray(item))
                else         -> arr.put(item)
            }
        }
        return arr
    }

    /** Recursively convert a [JSONObject] into a plain [Map]. */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = jsonValueToKotlin(obj.get(key))
        }
        return map
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            list.add(jsonValueToKotlin(arr.get(i)))
        }
        return list
    }

    private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
        JSONObject.NULL      -> null
        is JSONObject        -> jsonObjectToMap(value)
        is org.json.JSONArray -> jsonArrayToList(value)
        else                 -> value
    }
}

private const val MAX_PENDING_QUEUE_SIZE = 128
private val COLLAPSIBLE_PENDING_TYPES = setOf(
    "list-sessions",
    "acp-list-sessions",
    "get-favorites",
    "get-tags",
    "get-tag-assignments",
    "list-cron-jobs",
    "list-dotfiles",
    "get-stats",
    "get-claude-usage",
    "get-codex-usage",
)
