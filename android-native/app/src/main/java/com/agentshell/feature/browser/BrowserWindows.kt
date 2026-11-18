package com.agentshell.feature.browser

import com.agentshell.data.repository.BrowserFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ChromeTab(
    val id: Int, val title: String, val active: Boolean, val pinned: Boolean,
    val groupId: Int, val splitId: Int
) {
    val paired get() = splitId != -1
    fun canPairWith(other: ChromeTab) = id != other.id && !paired && !other.paired &&
        pinned == other.pinned && groupId == other.groupId
}

internal data class ChromeWindow(val id: Int, val number: Int, val focused: Boolean, val tabs: List<ChromeTab>) {
    val name get() = "Window $number"
    val activeTab get() = tabs.firstOrNull { it.active }
}

internal data class ChromeWindows(val revision: String, val splitSupported: Boolean, val windows: List<ChromeWindow>) {
    val focus get() = windows.singleOrNull { it.focused }?.let { window -> window.activeTab?.let { window.id to it.id } }
}

private fun <T> JSONArray.readRows(read: (JSONObject) -> T): List<T> = List(length()) { read(getJSONObject(it)) }

internal fun readChromeWindows(data: JSONObject, number: (Int) -> Int): ChromeWindows {
    val windows = data.getJSONArray("windows").readRows { row ->
        val id = row.getInt("id")
        ChromeWindow(id, number(id), row.getBoolean("focused"), row.getJSONArray("tabs").readRows { tab ->
            ChromeTab(tab.getInt("id"), tab.optString("title").ifBlank { "Untitled tab" }, tab.getBoolean("active"),
                tab.getBoolean("pinned"), tab.getInt("groupId"), tab.getInt("splitId"))
        })
    }
    return ChromeWindows(data.getString("revision"), data.getBoolean("splitSupported"), windows)
}

internal enum class WindowOperation { FOCUS, MOVE, DETACH, MERGE, PAIR, UNPAIR }

internal data class WindowAction(
    val operation: WindowOperation, val revision: String, val windowId: Int? = null, val tabId: Int? = null,
    val targetWindowId: Int? = null, val otherTabId: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().put("action", operation.name.lowercase()).put("revision", revision)
        .put("requestId", UUID.randomUUID().toString()).apply {
            windowId?.let { put("windowId", it) }
            tabId?.let { put("tabId", it) }
            targetWindowId?.let { put("targetWindowId", it) }
            otherTabId?.let { put("otherTabId", it) }
        }
}

internal data class BrowserWindowsState(
    val visible: Boolean = false, val pending: Boolean = false, val catalog: ChromeWindows? = null,
    val readError: String? = null, val actionError: String? = null
) {
    val label get() = catalog?.let { "Windows (${it.windows.size})" } ?: "Windows"
}

internal fun browserWindowError(code: String): String = when (code) {
    "WINDOW_CATALOG_CHANGED", "WINDOW_CATALOG_STALE", "WINDOW_TARGET_CHANGED" -> "The tabs changed. Check the current windows and choose again."
    "WINDOW_OPERATION_PARTIAL" -> "Chrome completed part of the action. Review the current tabs."
    "CONTROLLER_CHANGED", "CONTROLLER_IN_USE", "VIEWER_AUTHORIZATION_REQUIRED", "RUNTIME_CHANGED", "STALE_RUNTIME" ->
        "Browser control changed. Reconnect to manage these windows."
    "WINDOW_EXTENSION_OFFLINE" -> "Enable AgentShell Browser Windows in the selected Chrome profile. For a local install, open chrome://extensions, enable Developer mode, and load the unpacked extension from ~/.local/share/agentshell/browser-windows/extension. Then refresh."
    "WINDOW_PROFILE_REQUIRED" -> "Select a Chrome profile in the Browser toolbar, then open Windows again."
    "WINDOW_PAIRING_IN_PROGRESS" -> "Connecting to this Chrome profile…"
    "WINDOW_PAIRING_REQUIRED" -> "Open Windows or tap Refresh to connect this Chrome profile."
    "WINDOW_PAIRING_FAILED" -> "Enable AgentShell Browser Windows in this Chrome profile, then refresh."
    "WINDOW_SPLIT_UNAVAILABLE" -> "This version of Chrome cannot manage split views."
    "WINDOW_MERGE_NEEDS_SPLIT_SUPPORT" -> "Separate the paired tabs before merging windows on this version of Chrome."
    "WINDOW_PAIRED_TAB" -> "Exit side by side before moving a single tab from this pair."
    "WINDOW_ACTION_BUSY" -> "Chrome is rearranging tabs. Wait a moment and try again."
    "WINDOW_FOCUS_FAILED" -> "Chrome did not confirm which window is in front. Refresh to check it."
    "WINDOW_CATALOG_TOO_LARGE" -> "There are too many open tabs for the window picker."
    else -> when {
        code.contains("UNPAIRED") || code.contains("PAIRING") || code.contains("EXTENSION") ||
            code.contains("BRIDGE") || code == "WINDOW_MANAGEMENT_UNAVAILABLE" ->
            "Enable the AgentShell window manager extension in the selected Chrome profile, then refresh."
        else -> "Chrome could not complete the window request. Refresh the list and try again."
    }
}

/** Lives only while this viewer controls this runtime. Chrome IDs and titles never go to disk. */
internal class BrowserWindowManager(private val onFocusChange: () -> Unit) {
    private val _state = MutableStateFlow(BrowserWindowsState())
    val state = _state.asStateFlow()
    private class Connection(val scope: CoroutineScope, val request: suspend (JSONObject?, Boolean) -> JSONObject) {
        val lock = Mutex()
        val numbers = mutableMapOf<Int, Int>()
        var nextNumber = 1
        fun number(id: Int) = numbers.getOrPut(id) { nextNumber++ }
    }

    private var connection: Connection? = null

    suspend fun observe(scope: CoroutineScope, request: suspend (JSONObject?, Boolean) -> JSONObject) {
        val current = Connection(scope, request)
        connection = current
        try {
            while (scope.isActive) {
                refresh(current)
                val state = _state.value
                delay(when {
                    state.visible -> 2_000L
                    state.readError != null -> 30_000L
                    else -> 6_000L
                })
            }
        } finally {
            if (current === connection) {
                connection = null
                _state.value = BrowserWindowsState()
            }
        }
    }

    fun open() {
        val current = connection ?: return
        _state.update { it.copy(visible = true, actionError = null) }
        current.scope.launch { refresh(current, allowPairing = true) }
    }

    fun close() {
        if (!_state.value.pending) _state.update { it.copy(visible = false, actionError = null) }
    }

    fun reload() {
        connection?.let { current -> current.scope.launch { refresh(current, allowPairing = true) } }
    }

    suspend fun freshFocus(): Pair<Int, Int>? = connection?.let { refresh(it)?.focus }

    private fun publish(current: Connection, result: JSONObject): ChromeWindows {
        val snapshot = readChromeWindows(result.getJSONObject("snapshot"), current::number)
        val previous = _state.value.catalog
        if (previous != null && previous.focus != snapshot.focus) onFocusChange()
        current.numbers.keys.retainAll(snapshot.windows.map { it.id }.toSet())
        _state.update { it.copy(catalog = snapshot, readError = null) }
        return snapshot
    }

    private suspend fun refresh(current: Connection, allowPairing: Boolean = false): ChromeWindows? = current.lock.withLock {
        if (current !== connection) return@withLock null
        try {
            val result = current.request(null, allowPairing)
            if (current !== connection) return@withLock null
            if (!result.optBoolean("ok")) throw BrowserFailure(result.optString("code"))
            publish(current, result)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current === connection) {
                if (_state.value.catalog != null) onFocusChange()
                _state.update { it.copy(catalog = null, readError = browserWindowError((error as? BrowserFailure)?.code.orEmpty())) }
            }
            null
        }
    }

    fun perform(action: WindowAction) {
        val current = connection ?: return
        if (_state.value.pending || !_state.value.visible) return
        if (action.revision != _state.value.catalog?.revision) {
            _state.update { it.copy(actionError = browserWindowError("WINDOW_CATALOG_CHANGED")) }
            return
        }
        onFocusChange()
        _state.update { it.copy(pending = true, actionError = null) }
        current.scope.launch {
            var needsRefresh = false
            current.lock.withLock {
                if (current !== connection) return@launch
                try {
                    val result = current.request(action.toJson(), false)
                    if (current !== connection) return@launch
                    if (result.has("snapshot")) publish(current, result) else needsRefresh = true
                    if (result.optBoolean("ok")) {
                        _state.update { it.copy(visible = false, pending = false) }
                    } else {
                        val message = if (result.optString("reason") == "WINDOW_PAIR_RESTORE_FAILED")
                            "Chrome could not restore a side-by-side pair. Select the two tabs to pair them again."
                        else browserWindowError(result.optString("code"))
                        val counts = if (result.has("moved") && result.has("total"))
                            "${result.getInt("moved")} of ${result.getInt("total")} tabs moved. " else ""
                        _state.update { it.copy(actionError = counts + message, pending = false) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current !== connection) return@launch
                    needsRefresh = true
                    _state.update { it.copy(actionError = browserWindowError((error as? BrowserFailure)?.code.orEmpty()), pending = false) }
                }
            }
            if (needsRefresh) refresh(current)
        }
    }
}
