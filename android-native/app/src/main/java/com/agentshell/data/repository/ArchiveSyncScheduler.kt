package com.agentshell.data.repository

import com.agentshell.data.local.ArchiveSyncWork
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ArchiveSyncResult {
    data object Complete : ArchiveSyncResult
    data object Continue : ArchiveSyncResult
    data class Retry(val minimumDelayMillis: Long = 60_000) : ArchiveSyncResult
}

internal class ArchiveBudgetPause : Exception()

/** Persisted per-target scheduling; a continuation cannot turn into an inventory sweep. */
internal class ArchiveSyncScheduler(
    private val store: Store,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    interface Store {
        suspend fun get(key: String): ArchiveSyncWork?
        suspend fun put(work: ArchiveSyncWork)
        suspend fun next(hostId: String, now: Long, preferredKey: String?): ArchiveSyncWork?
    }

    private val mutex = Mutex()
    private var turns = 0L

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(work: ArchiveSyncWork, complete: Boolean) = mutex.withLock {
        val old = store.get(work.workKey)
        if (old == null) {
            // Local completeness cannot prove that the remote has not changed.
            // Reconcile once on first discovery, then persist the successful cooldown.
            store.put(work.copy(nextRunAt = nowMillis()))
        } else if (old.sourceHint != work.sourceHint || old.targetJson != work.targetJson) {
            // Only source-hint changes invalidate cooldown. Titles/cwd are metadata.
            store.put(old.copy(targetJson = work.targetJson, sourceHint = work.sourceHint,
                nextRunAt = if (old.sourceHint != work.sourceHint) nowMillis() else old.nextRunAt,
                attempts = if (old.sourceHint != work.sourceHint) 0 else old.attempts,
                generation = if (old.sourceHint != work.sourceHint) old.generation + 1 else old.generation))
        }
    }

    suspend fun invalidate(key: String) = mutex.withLock {
        val work = store.get(key) ?: return@withLock
        store.put(work.copy(nextRunAt = minOf(work.nextRunAt, nowMillis()), attempts = 0, generation = work.generation + 1))
    }

    suspend fun next(hostId: String, preferredKey: String?): ArchiveSyncWork? = mutex.withLock {
        // Foreground priority uses one turn in four; archive backlog cannot starve.
        val preferred = preferredKey.takeIf { turns++ % 4 == 0L }
        store.next(hostId, nowMillis(), preferred)?.let { work ->
            work.copy(lastRunAt = nowMillis().coerceAtLeast(1)).also { store.put(it) }
        }
    }

    suspend fun finish(work: ArchiveSyncWork, result: ArchiveSyncResult) = mutex.withLock {
        val current = store.get(work.workKey) ?: return@withLock
        // A changed catalog during a request leaves that target due for revalidation.
        if (current.generation != work.generation) return@withLock
        val attempts = if (result is ArchiveSyncResult.Retry) (current.attempts + 1).coerceAtMost(8) else 0
        val delay = when (result) {
            ArchiveSyncResult.Complete -> COMPLETE_CHECK_INTERVAL_MS
            ArchiveSyncResult.Continue -> CONTINUATION_DELAY_MS
            is ArchiveSyncResult.Retry -> maxOf(result.minimumDelayMillis,
                (60_000L * (1L shl (attempts - 1).coerceAtLeast(0))).coerceAtMost(COMPLETE_CHECK_INTERVAL_MS))
        }
        store.put(current.copy(nextRunAt = nowMillis() + delay, attempts = attempts))
    }

    companion object {
        const val COMPLETE_CHECK_INTERVAL_MS = 30 * 60_000L
        const val CONTINUATION_DELAY_MS = 1_500L
    }
}

/** One shared request window survives reconnect, host and visibility changes. */
internal class ArchiveRequestBudget(
    private val foreground: () -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var windowStarted = Long.MIN_VALUE
    private var used = 0

    @Synchronized fun availableInMillis(permits: Int = 1): Long {
        val now = nowMillis()
        if (windowStarted == Long.MIN_VALUE || now - windowStarted >= WINDOW_MS) {
            windowStarted = now
            used = 0
        }
        val limit = if (foreground()) FOREGROUND_REQUESTS_PER_MINUTE else BACKGROUND_REQUESTS_PER_MINUTE
        return if (used + permits <= limit) 0 else (WINDOW_MS - (now - windowStarted)).coerceAtLeast(1)
    }

    @Synchronized fun tryAcquire(permits: Int = 1): Boolean {
        require(permits in 1..BACKGROUND_REQUESTS_PER_MINUTE)
        if (availableInMillis(permits) != 0L) return false
        used += permits
        return true
    }

    suspend fun acquire(permits: Int = 1) {
        require(permits in 1..BACKGROUND_REQUESTS_PER_MINUTE)
        while (true) {
            val wait = synchronized(this) {
                availableInMillis(permits).also { if (it == 0L) used += permits }
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    companion object {
        const val BACKGROUND_REQUESTS_PER_MINUTE = 32
        const val FOREGROUND_REQUESTS_PER_MINUTE = 128
        private const val WINDOW_MS = 60_000L
    }
}
