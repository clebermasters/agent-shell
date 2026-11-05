package com.agentshell.feature.chat

import kotlinx.coroutines.*

internal data class ChatDraftSnapshot(val text: String, val serial: Long)

/** Accepted draft writes finish in order even if their chat is immediately disposed. */
internal class ChatDraftWriter(
    private val persist: suspend (key: String, text: String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onError: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val latest = mutableMapOf<String, ChatDraftSnapshot>()
    private var serial = 0L
    private var tail: Job? = null
    private var closed = false

    fun save(key: String, text: String): Long = synchronized(lock) {
        if (closed) return@synchronized serial
        val version = ++serial
        latest[key] = ChatDraftSnapshot(text, version)
        tail = sequence(scope) {
            val value = synchronized(lock) { latest[key]?.takeIf { it.serial == version } } ?: return@sequence
            try { persist(key, value.text) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError() }
        }
        version
    }

    fun snapshot(key: String): ChatDraftSnapshot? = synchronized(lock) { latest[key] }

    /** Delivery/edit metadata must outlive navigation just like its accepted text write. */
    fun afterWrites(action: suspend () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        tail = sequence(scope) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError() }
        }
    }

    fun forget(key: String, expectedSerial: Long? = null) = synchronized(lock) {
        if (expectedSerial == null || latest[key]?.serial == expectedSerial) latest.remove(key)
        Unit
    }

    suspend fun restore(key: String, readSaved: suspend () -> String?): String? {
        snapshot(key)?.let { return it.text }
        val saved = readSaved()
        return snapshot(key)?.text ?: saved
    }

    /** Barrier for all writes accepted by this writer before a key migration. */
    suspend fun flush() { synchronized(orderLock) { previous }?.join() }

    fun close() {
        val last = synchronized(lock) {
            if (closed) return
            closed = true
            tail
        }
        if (last == null) scope.cancel()
        else scope.launch { last.join(); scope.cancel() }
    }

    companion object {
        private val orderLock = Any()
        private var previous: Job? = null

        /** Global acceptance order also protects a new view from an older view's final clear. */
        private fun sequence(scope: CoroutineScope, write: suspend () -> Unit): Job = synchronized(orderLock) {
            val before = previous
            scope.launch { before?.join(); write() }.also {
                previous = it
            }
        }
    }
}
