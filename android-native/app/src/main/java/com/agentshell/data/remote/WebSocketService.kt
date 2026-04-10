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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketService @Inject constructor(
    baseClient: OkHttpClient,
) {

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
    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var activeListenerId = 0L  // Monotonic ID to ignore stale listener callbacks

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    @Volatile
    private var _isConnecting = false  // Guards against duplicate connect() calls

    private var reconnectAttempts = 0
    private var intentionalClose = false

    // Pending messages buffered while disconnected
    private val pendingQueue = ConcurrentLinkedQueue<String>()

    // Jobs for ping / pong-timeout / reconnect timers
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    // -------------------------------------------------------------------------
    // Public flows
    // -------------------------------------------------------------------------
    private val _messages = MutableSharedFlow<Map<String, Any?>>(replay = 0, extraBufferCapacity = 256)
    val messages: SharedFlow<Map<String, Any?>> = _messages.asSharedFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

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
        // Skip if already connected or connecting to the same URL
        if (finalUrl == currentUrl && (_isConnected || _isConnecting)) {
            log("Already connected/connecting to ${sanitizeUrl(finalUrl)}, skipping")
            return
        }
        // Disconnect from previous host if switching
        if (currentUrl != null && currentUrl != finalUrl && _isConnected) {
            log("Switching host — disconnecting from previous")
            webSocket?.close(1000, "Switching host")
            webSocket = null
            _isConnected = false
            cancelTimers()
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
        cancelTimers()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _isConnected = false
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.CONNECTING
    }

    /**
     * Cancel any pending reconnect, close the current socket immediately,
     * and reconnect from scratch (mirrors Flutter's forceReconnect()).
     */
    fun forceReconnect() {
        if (currentUrl == null) return
        log("Forcing immediate reconnect...")
        intentionalClose = false
        cancelTimers()
        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
        _isConnected = false
        reconnectAttempts = 0
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
            pendingQueue.add(json)
            // Only trigger reconnect if not already connecting/reconnecting
            if (!_isConnecting && (reconnectJob == null || reconnectJob!!.isCompleted)) {
                scheduleReconnect()
            }
        }
    }

    /**
     * Check connection health: if disconnected, force reconnect immediately;
     * if connected, send a ping to verify the connection is still alive.
     * Called when the app returns to foreground.
     */
    fun checkConnection() {
        if (_isConnected) {
            send(mapOf("type" to "ping"))
        } else if (currentUrl != null) {
            forceReconnect()
        }
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
        log("Attempting WebSocket connection to: ${sanitizeUrl(url)}")

        val listenerId = ++activeListenerId
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, Listener(listenerId))
    }

    private fun scheduleReconnect() {
        // Don't schedule if already connected (race with successful reconnect)
        if (_isConnected) return
        // Exponential backoff: 2s, 4s, 8s, 16s, cap at 30s — never give up
        val delayMs = (2_000L * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        log("Scheduling reconnect #$reconnectAttempts in ${delayMs}ms...")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            doConnect()
        }
    }

    private fun cancelTimers() {
        pingJob?.cancel();         pingJob = null
        pongTimeoutJob?.cancel();  pongTimeoutJob = null
        reconnectJob?.cancel();    reconnectJob = null
    }

    private fun startPingTimer() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(30_000)
                if (!_isConnected) break
                send(mapOf("type" to "ping"))
                // Expect a pong within 20 s
                pongTimeoutJob?.cancel()
                pongTimeoutJob = scope.launch {
                    delay(20_000)
                    println("[CONN] Android→Backend PONG TIMEOUT — forcing reconnect")
                    forceReconnect()
                }
            }
        }
    }

    private fun flushPendingQueue() {
        if (pendingQueue.isEmpty()) return
        val ws = webSocket ?: return
        val snapshot = mutableListOf<String>()
        while (true) {
            snapshot.add(pendingQueue.poll() ?: break)
        }
        log("Flushing ${snapshot.size} queued message(s) after reconnect")
        for (json in snapshot) {
            ws.send(json)
        }
    }

    // -------------------------------------------------------------------------
    // WebSocketListener implementation
    // -------------------------------------------------------------------------

    private inner class Listener(private val id: Long) : WebSocketListener() {

        /** Returns true if this listener has been superseded by a newer connection. */
        private fun isStale(): Boolean = id != activeListenerId

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale()) return
            println("[CONN] Android→Backend CONNECTED to ${sanitizeUrl(currentUrl ?: "")}")
            _isConnecting = false
            _isConnected = true
            reconnectAttempts = 0
            _connectionStatus.value = ConnectionStatus.CONNECTED
            flushPendingQueue()
            startPingTimer()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale()) return
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "")

                // Cancel pong-timeout when a pong arrives
                if (type == "pong") {
                    pongTimeoutJob?.cancel()
                    pongTimeoutJob = null
                }

                // Handle server graceful shutdown — reconnect after the suggested delay
                if (type == "server-shutting-down") {
                    val delayMs = json.optLong("reconnectDelayMs", 2000L)
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

                val messageMap = jsonObjectToMap(json)
                scope.launch { _messages.emit(messageMap) }
            } catch (e: Exception) {
                log("Failed to parse message: $e")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isStale() || intentionalClose) return
            println("[CONN] Android→Backend WebSocket ERROR: $t (url: ${sanitizeUrl(currentUrl ?: "")})")
            _isConnecting = false
            _isConnected = false
            reconnectAttempts++
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale() || intentionalClose) return
            println("[CONN] Android→Backend WebSocket CLOSED (url: ${sanitizeUrl(currentUrl ?: "")})")
            _isConnecting = false
            _isConnected = false
            reconnectAttempts++
            scheduleReconnect()
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
