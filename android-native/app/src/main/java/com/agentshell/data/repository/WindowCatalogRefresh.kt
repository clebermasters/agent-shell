package com.agentshell.data.repository

/** Duplicate display catalogs cannot turn into a window query on every broadcast. */
internal class WindowCatalogRefresh(private val intervalMillis: Long = 60_000) {
    private data class Key(val hostId: String, val session: String)
    private data class Entry(val created: String?, val windows: Int?, val requestedAt: Long)
    private val entries = mutableMapOf<Key, Entry>()

    fun catalog(hostId: String, session: String, created: String?, windows: Int?, now: Long): Boolean {
        val key = Key(hostId, session)
        val old = entries[key]
        if (old != null && old.created == created && old.windows == windows && now - old.requestedAt < intervalMillis) return false
        entries[key] = Entry(created, windows, now)
        return true
    }

    fun due(hostId: String, session: String, now: Long): Boolean {
        val key = Key(hostId, session)
        val old = entries[key]
        if (old != null && now - old.requestedAt < intervalMillis) return false
        entries[key] = old?.copy(requestedAt = now) ?: Entry(null, null, now)
        return true
    }

    fun requested(hostId: String, session: String, now: Long) {
        val key = Key(hostId, session)
        entries[key] = entries[key]?.copy(requestedAt = now) ?: Entry(null, null, now)
    }

    fun nextDelay(hostId: String, sessions: Collection<String>, now: Long): Long = sessions.minOfOrNull { session ->
        val requested = entries[Key(hostId, session)]?.requestedAt ?: (now - intervalMillis)
        (requested + intervalMillis - now).coerceAtLeast(1)
    } ?: intervalMillis

    fun retain(hostId: String, sessions: Set<String>) {
        entries.keys.removeAll { it.hostId != hostId || it.session !in sessions }
    }

    fun clear() = entries.clear()
}
