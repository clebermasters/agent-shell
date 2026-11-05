package com.agentshell.data.remote

import com.agentshell.data.model.ConnectionStatus
import com.agentshell.core.diagnostics.BatteryDiagnostics
import com.agentshell.core.diagnostics.DiagnosticSocketRole
import com.agentshell.core.power.ConnectionNetworkGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.collect
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
class WebSocketService internal constructor(
    baseClient: OkHttpClient,
    private val networkAllowed: StateFlow<Boolean>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(baseClient: OkHttpClient) : this(baseClient, ConnectionNetworkGate.allowed)

    private val lifecycleLock = Any()
    private val diagnosticToken = Any()
    private val diagnosticRole = DiagnosticSocketRole.PRIMARY
    private data class PendingMessage(
        val type: String?,
        val payload: String,
    )


    // -------------------------------------------------------------------------
    // Coroutine scope – SupervisorJob so one child failure doesn't cancel others
    // -------------------------------------------------------------------------
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

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
    val isConnected: Boolean get() = _isConnected && mayConnect()

    @Volatile
    private var _isConnecting = false  // Guards against duplicate connect() calls

    private var reconnectAttempts = 0
    private var intentionalClose = false
    @Volatile private var backgroundSuspended = false
    @Volatile private var disposed = false

    // Pending messages buffered while disconnected
    private val pendingQueue = ArrayDeque<PendingMessage>()
    private val pendingQueueLock = Any()

    // Jobs for ping / pong-timeout / reconnect timers
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val heartbeat = WebSocketHeartbeat(scope) { connection ->
        if (connection == activeListenerId && _isConnected && mayConnect()) {
            BatteryDiagnostics.event("heartbeat_timeout", diagnosticRole)
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
    private val _connectionDesired = MutableStateFlow(false)
    val connectionDesired: StateFlow<Boolean> = _connectionDesired.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            networkAllowed.collect {
                // Reopening the gate alone must not defeat a timeout or denied-start latch.
                // Re-read under the lifecycle lock: an emitted false may have
                // waited behind a newer authorized resume on another thread.
                synchronized(lifecycleLock) {
                    if (!networkAllowed.value) suspendForBackground()
                }
            }
        }
    }

    private fun mayConnect() = !disposed && _connectionDesired.value && !backgroundSuspended && networkAllowed.value

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
        synchronized(lifecycleLock) {
            if (disposed) return
            var finalUrl = url
            if (authToken != null && authToken.isNotEmpty()) {
                val separator = if (finalUrl.contains('?')) "&" else "?"
                finalUrl = "$finalUrl${separator}token=${android.net.Uri.encode(authToken)}"
            }
            _connectionDesired.value = true
            if (!networkAllowed.value) backgroundSuspended = true
            // Skip if already connected or connecting to the same URL
            if (finalUrl == currentUrl && (_isConnected || _isConnecting) && mayConnect()) {
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
            if (!mayConnect()) {
                stopTransportLocked()
                return
            }
            _keepAliveEnabled.value = true
            reconnectAttempts = 0
            intentionalClose = false
            log("Connecting to: ${sanitizeUrl(finalUrl)}")
            doConnect()
        }
    }

    /** Close the WebSocket cleanly; does NOT schedule a reconnect. */
    fun disconnect() {
        synchronized(lifecycleLock) {
            log("Disconnecting...")
            _connectionDesired.value = false
            stopTransportLocked(graceful = true)
        }
    }

    /** An OS/policy pause preserves intent, but nothing may reconnect until foreground recovery. */
    @Suppress("UNUSED_PARAMETER")
    fun suspendForBackground(reason: String = "background-policy") {
        synchronized(lifecycleLock) {
            backgroundSuspended = true
            stopTransportLocked()
        }
    }

    /** Only the coordinator's authorized foreground/live transition clears the pause latch. */
    fun resumeAfterPolicyPause() {
        synchronized(lifecycleLock) {
            if (disposed || !networkAllowed.value) return
            val wasSuspended = backgroundSuspended
            backgroundSuspended = false
            if (!wasSuspended || !_connectionDesired.value) return
            _keepAliveEnabled.value = true
            intentionalClose = false
            if (_isConnected) probeConnection(10_000)
            else { reconnectAttempts = 0; doConnect() }
        }
    }

    fun resumeFromForeground() = resumeAfterPolicyPause()

    private fun stopTransportLocked(graceful: Boolean = false) {
        intentionalClose = true
        _keepAliveEnabled.value = false
        cancelTimers()
        activeListenerId++
        activeListener?.retire()
        activeListener = null
        val old = webSocket
        webSocket = null
        _isConnected = false
        _isConnecting = false
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.OFFLINE
        synchronized(pendingQueueLock) { pendingQueue.clear() }
        if (graceful) old?.close(1000, "Client disconnect") else old?.cancel()
    }

    /**
     * Cancel any pending reconnect, close the current socket immediately,
     * and reconnect from scratch (mirrors Flutter's forceReconnect()).
     */
    fun forceReconnect() {
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
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
    }

    /**
     * Send a message map. If connected, serialises and sends immediately.
     * Otherwise queues it and triggers a reconnect.
     */
    fun send(message: Map<String, Any?>) {
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
            val json = mapToJson(message).toString()
            if (_isConnected && webSocket != null) {
                sendObserved(webSocket!!, json, message["type"] as? String)
            } else {
                enqueuePendingMessage(message["type"] as? String, json)
                // Only trigger reconnect if not already connecting/reconnecting
                if (!_isConnecting && (reconnectJob == null || reconnectJob!!.isCompleted)) {
                    scheduleReconnect()
                }
            }
        }
    }

    /** Send a transfer only to its original server; never queue it for a later connection. */
    fun sendIfConnected(message: Map<String, Any?>, expectedServerUrl: String): Boolean {
        synchronized(lifecycleLock) {
            val socket = webSocket ?: return false
            if (!mayConnect() || !_isConnected || currentUrl != expectedServerUrl) return false
            val json = mapToJson(message).toString()
            if (!_isConnected || currentUrl != expectedServerUrl || webSocket !== socket) return false
            return sendObserved(socket, json, message["type"] as? String)
        }
    }

    private fun sendObserved(socket: WebSocket, text: String, type: String? = null): Boolean {
        if (!mayConnect()) return false
        val accepted = socket.send(text)
        if (accepted) BatteryDiagnostics.message(diagnosticRole, false, text, type)
        return accepted
    }

    /**
     * Check connection health: if disconnected, force reconnect immediately;
     * if connected, send a ping to verify the connection is still alive.
     * Called when the app returns to foreground.
     */
    fun checkConnection() {
        synchronized(lifecycleLock) {
            if (!mayConnect() || _isConnecting) return
            log("checkConnection status=${_connectionStatus.value} connected=$_isConnected connecting=$_isConnecting url=${sanitizeUrl(currentUrl ?: "")}")
            if (_isConnected) {
                probeConnection(10_000)
            } else if (currentUrl != null) {
                forceReconnect()
            }
        }
    }

    fun onNetworkAvailable() {
        if (!mayConnect() || !_keepAliveEnabled.value || currentUrl == null) return
        log("Network available — checking websocket health")
        checkConnection()
    }

    /** Release all resources permanently. */
    fun dispose() {
        synchronized(lifecycleLock) {
            _connectionDesired.value = false
            disposed = true
            stopTransportLocked(graceful = true)
        }
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal connection management
    // -------------------------------------------------------------------------

    private fun doConnect() {
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
            val url = currentUrl ?: return

            // Don't open a new socket if we're already connected or mid-handshake
            if (_isConnected || _isConnecting) {
                log("doConnect() skipped — already connected/connecting")
                return
            }

            // Publish admission before a reactive status observer can request
            // another health check on this same handshake.
            _isConnecting = true

            // Emit the correct status based on attempt count (mirrors Flutter exactly)
            _connectionStatus.value = when {
                reconnectAttempts == 0 -> ConnectionStatus.CONNECTING
                reconnectAttempts < 3  -> ConnectionStatus.RECONNECTING
                else                   -> ConnectionStatus.OFFLINE
            }

            log("Attempting WebSocket connection to: ${sanitizeUrl(url)} reconnectAttempts=$reconnectAttempts pendingQueue=${pendingQueueSize()}")

            val listenerId = ++activeListenerId
            val request = Request.Builder().url(url).build()
            activeListener?.retire()
            val listener = Listener(listenerId)
            activeListener = listener
            BatteryDiagnostics.event("socket_attempt", diagnosticRole)
            webSocket = client.newWebSocket(request, listener)
        }
    }

    private fun scheduleReconnect() {
        synchronized(lifecycleLock) {
            // Don't schedule if already connected (race with successful reconnect)
            if (!mayConnect() || _isConnected || !_keepAliveEnabled.value) return
            BatteryDiagnostics.event("reconnect_scheduled", diagnosticRole)
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
        synchronized(lifecycleLock) {
            if (!mayConnect()) return
            BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
            _isConnecting = false
            _isConnected = false
            cancelHeartbeat()
            reconnectAttempts++
            log("$reason reconnectAttempts=$reconnectAttempts")
            scheduleReconnect()
        }
    }

    private fun startPingTimer() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(30_000)
                if (!_isConnected || !mayConnect()) break
                probeConnection(20_000)
            }
        }
    }

    private fun probeConnection(timeoutMillis: Long) {
        val socket = webSocket ?: return
        val connection = activeListenerId
        if (!_isConnected || !mayConnect()) return
        heartbeat.ping(connection, timeoutMillis) {
            _isConnected && connection == activeListenerId && webSocket === socket && sendObserved(socket, "{\"type\":\"ping\"}", "ping")
        }
    }

    private fun flushPendingQueue(socket: WebSocket, listenerId: Long) {
        val snapshot = synchronized(pendingQueueLock) {
            if (!mayConnect() || listenerId != activeListenerId || webSocket !== socket) return
            if (pendingQueue.isEmpty()) return
            val drained = pendingQueue.toList()
            pendingQueue.clear()
            drained
        }
        log("Flushing ${snapshot.size} queued message(s) after reconnect")
        for (pending in snapshot) {
            if (!mayConnect() || listenerId != activeListenerId) return
            sendObserved(socket, pending.payload, pending.type)
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

        fun retire() {
            BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
            incoming.cancel()
        }

        /** Returns true if this listener has been superseded by a newer connection. */
        private fun isStale(): Boolean = id != activeListenerId || !mayConnect()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lifecycleLock) {
                if (isStale()) { webSocket.cancel(); return }
                BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, true)
                BatteryDiagnostics.event("socket_open", diagnosticRole)
                log("onOpen listenerId=$id url=${sanitizeUrl(currentUrl ?: "")} pendingQueue=${pendingQueueSize()}")
                println("[CONN] Android→Backend CONNECTED to ${sanitizeUrl(currentUrl ?: "")}")
                _isConnecting = false
                _isConnected = true
                reconnectAttempts = 0
                _connectionGeneration.update { it + 1 }
                _connectionStatus.value = ConnectionStatus.CONNECTED
                flushPendingQueue(webSocket, id)
                startPingTimer()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale()) return
            var diagnosticType: String? = null
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "")
                diagnosticType = type

                // Cancel pong-timeout when a pong arrives
                if (type == "pong") {
                    heartbeat.pong(id)
                }

                // Handle server graceful shutdown — reconnect after the suggested delay
                if (type == "server-shutting-down") {
                    synchronized(lifecycleLock) {
                        if (isStale()) return
                        BatteryDiagnostics.socket(diagnosticToken, diagnosticRole, false)
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
                }

                val messageMap = jsonObjectToMap(json) + ("_sourceServerUrl" to sourceServerUrl)
                incoming.accept(messageMap)
            } catch (e: Exception) {
                log("Failed to parse message: $e")
            } finally {
                // Count malformed frames too, and count every frame only once.
                BatteryDiagnostics.message(diagnosticRole, true, text, diagnosticType)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close()
            synchronized(lifecycleLock) {
                if (isStale() || intentionalClose || serverRestartScheduled) return
                BatteryDiagnostics.event("socket_failed", diagnosticRole)
                log("onFailure listenerId=$id error=${t.message} reconnectAttempts=${reconnectAttempts + 1} url=${sanitizeUrl(currentUrl ?: "")}")
                println("[CONN] Android→Backend WebSocket ERROR: $t (url: ${sanitizeUrl(currentUrl ?: "")})")
                handleUnexpectedDisconnect("WebSocket failure")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale()) { webSocket.cancel(); return }
            incoming.close()
            // OkHttp delivers onClosed only after we acknowledge the peer's
            // close. An empty close frame reports reserved status 1005.
            webSocket.close(if (code == 1005) 1000 else code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close()
            synchronized(lifecycleLock) {
                if (isStale() || intentionalClose || serverRestartScheduled) return
                BatteryDiagnostics.event("socket_closed", diagnosticRole)
                log("onClosed listenerId=$id code=$code reason=$reason reconnectAttempts=${reconnectAttempts + 1} url=${sanitizeUrl(currentUrl ?: "")}")
                println("[CONN] Android→Backend WebSocket CLOSED (url: ${sanitizeUrl(currentUrl ?: "")})")
                handleUnexpectedDisconnect("WebSocket closed code=$code reason=$reason")
            }
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
