package com.agentshell.data.services

import android.util.Log
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.attachSession
import com.agentshell.data.remote.resizeTerminal
import com.agentshell.data.remote.sendTerminalData
import com.agentshell.data.remote.sendInputViaTmux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TermService"

/**
 * Bridges WebSocket terminal messages to/from the XTermView (xterm.js WebView).
 *
 * No local terminal engine — xterm.js in the WebView handles all VT parsing,
 * buffering, and rendering. This service just pipes UTF-8 strings.
 *
 * Supports two modes:
 * 1. **Single-session** (original API) — one active session at a time.
 * 2. **Keyed multi-session** — multiple simultaneous sessions, each identified
 *    by a unique key (e.g., panel ID). Used by the split-screen feature.
 */
@Singleton
class TerminalService @Inject constructor(
    private val webSocketService: WebSocketService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Single-session API (backward compat) ────────────────────────────

    private var listenerJob: Job? = null
    private var activeSession: String? = null

    /** Callback to write data into xterm.js (set by ViewModel). */
    var onTerminalData: ((String) -> Unit)? = null

    /**
     * Start listening for WebSocket output messages for [sessionName]
     * and forward them to [onTerminalData].
     */
    fun attachSession(sessionName: String, cols: Int, rows: Int, windowIndex: Int = 0) {
        listenerJob?.cancel()
        activeSession = sessionName

        Log.d(TAG, "attachSession: $sessionName ${cols}x${rows}")

        webSocketService.attachSession(
            sessionName = sessionName,
            cols = cols,
            rows = rows,
            windowIndex = windowIndex,
        )

        listenerJob = scope.launch {
            webSocketService.messages.collect { message ->
                val type = message["type"] as? String ?: return@collect
                val msgSession = (message["sessionName"] as? String)
                    ?: (message["session"] as? String)

                if (msgSession != null && msgSession != sessionName) return@collect

                when (type) {
                    "output", "terminal_data",
                    "terminal-history-chunk" -> {
                        val data = message["data"] as? String ?: return@collect
                        withContext(Dispatchers.Main) {
                            onTerminalData?.invoke(data)
                        }
                    }
                }
            }
        }
    }

    /** Send user input from xterm.js to the backend. */
    fun sendInput(data: String) {
        webSocketService.sendTerminalData(data)
    }

    /** Notify backend of terminal resize. */
    fun resize(cols: Int, rows: Int) {
        Log.d(TAG, "resize: ${cols}x${rows}")
        webSocketService.resizeTerminal(cols, rows)
    }

    /** Detach from the current session. */
    fun detach() {
        listenerJob?.cancel()
        listenerJob = null
        activeSession = null
        onTerminalData = null
    }

    // ── Keyed multi-session API (split-screen) ──────────────────────────

    private data class SessionListener(
        val sessionName: String,
        val windowIndex: Int,
        val job: Job,
        var onData: ((String) -> Unit)?,
    )

    private val listeners = ConcurrentHashMap<String, SessionListener>()

    /**
     * Attach a keyed terminal session. Multiple keyed sessions can run
     * simultaneously — each filters WebSocket output by [sessionName].
     *
     * @param key         Unique identifier (e.g., panel ID).
     * @param sessionName The tmux session name.
     * @param cols        Terminal columns.
     * @param rows        Terminal rows.
     * @param windowIndex The tmux window index.
     * @param onData      Callback invoked on the Main thread with terminal data.
     */
    fun attachSessionKeyed(
        key: String,
        sessionName: String,
        cols: Int,
        rows: Int,
        windowIndex: Int = 0,
        onData: (String) -> Unit,
    ) {
        // Cancel any existing listener for this key
        detachKeyed(key)

        Log.d(TAG, "attachSessionKeyed: key=$key session=$sessionName ${cols}x${rows}")

        // Request history and attach on the backend
        webSocketService.attachSession(
            sessionName = sessionName,
            cols = cols,
            rows = rows,
            windowIndex = windowIndex,
        )

        val job = scope.launch {
            webSocketService.messages.collect { message ->
                val type = message["type"] as? String ?: return@collect
                val msgSession = (message["sessionName"] as? String)
                    ?: (message["session"] as? String)

                // Only handle messages for this session (or un-tagged output)
                if (msgSession != null && msgSession != sessionName) return@collect

                when (type) {
                    "output", "terminal_data",
                    "terminal-history-chunk" -> {
                        val data = message["data"] as? String ?: return@collect
                        withContext(Dispatchers.Main) {
                            listeners[key]?.onData?.invoke(data)
                        }
                    }
                }
            }
        }

        listeners[key] = SessionListener(
            sessionName = sessionName,
            windowIndex = windowIndex,
            job = job,
            onData = onData,
        )
    }

    /** Detach a specific keyed session. */
    fun detachKeyed(key: String) {
        listeners.remove(key)?.let { listener ->
            Log.d(TAG, "detachKeyed: key=$key session=${listener.sessionName}")
            listener.job.cancel()
            listener.onData = null
        }
    }

    /** Detach all keyed sessions. */
    fun detachAllKeyed() {
        listeners.keys.toList().forEach { detachKeyed(it) }
    }

    /**
     * Send input to a specific keyed session via tmux send-keys,
     * targeting the session by name.
     */
    fun sendInputKeyed(key: String, data: String) {
        val listener = listeners[key] ?: return
        webSocketService.sendInputViaTmux(
            data = data,
            sessionName = listener.sessionName,
            windowIndex = listener.windowIndex,
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun dispose() {
        detach()
        detachAllKeyed()
        scope.cancel()
    }
}
