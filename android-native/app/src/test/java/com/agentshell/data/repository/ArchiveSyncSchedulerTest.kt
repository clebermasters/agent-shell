package com.agentshell.data.repository

import com.agentshell.data.local.ArchiveSyncWork
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveSyncSchedulerTest {
    private class Store : ArchiveSyncScheduler.Store {
        val rows = linkedMapOf<String, ArchiveSyncWork>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(work: ArchiveSyncWork) { rows[work.workKey] = work }
        override suspend fun next(hostId: String, now: Long, preferredKey: String?) = rows.values
            .filter { it.hostId == hostId && it.nextRunAt <= now }
            .minWithOrNull(compareBy<ArchiveSyncWork> { if (it.workKey == preferredKey) 0 else 1 }
                .thenBy { it.lastRunAt }.thenBy { it.nextRunAt }.thenBy { it.workKey })
    }

    private fun work(key: String, host: String = "host", hint: String = "r1") =
        ArchiveSyncWork(key, host, "target:$key", "history", sourceHint = hint)

    @Test fun thirtyMinutesWith540DirectAndEightTerminalArchivesChecksEachAtMostOnce() = runTest {
        val store = Store()
        val scheduler = ArchiveSyncScheduler(store) { testScheduler.currentTime }
        val budget = ArchiveRequestBudget({ false }) { testScheduler.currentTime }
        repeat(548) { index -> scheduler.seed(work("target-${index.toString().padStart(3, '0')}"), complete = true) }
        val requests = mutableListOf<Pair<Long, String>>()
        val job = launch {
            while (isActive) {
                val next = scheduler.next("host", null)
                if (next == null) { delay(1_000); continue }
                budget.acquire()
                requests.add(testScheduler.currentTime to next.workKey)
                // A warm revision response is complete; no page/body request follows.
                scheduler.finish(next, ArchiveSyncResult.Complete)
            }
        }
        advanceTimeBy(30 * 60_000L)
        job.cancelAndJoin()
        assertEquals(548, requests.size)
        assertEquals(548, requests.map { it.second }.distinct().size)
        assertTrue(requests.groupBy { it.first / 60_000 }.values.all { it.size <= 32 })
        assertEquals(548, store.rows.size)
    }

    @Test fun partialArchiveContinuationNeverRescans547CompletedTargets() = runTest {
        val store = Store()
        val scheduler = ArchiveSyncScheduler(store) { testScheduler.currentTime }
        val budget = ArchiveRequestBudget({ false }) { testScheduler.currentTime }
        repeat(547) { store.put(work("old-$it")) }
        store.put(work("partial"))
        val checks = mutableMapOf<String, Int>()
        val requestTimes = mutableListOf<Long>()
        var savedOffset = 0
        val job = launch {
            while (isActive) {
                val next = scheduler.next("host", null)
                if (next == null) { delay(100); continue }
                val permits = if (next.workKey == "partial") 5 else 1
                budget.acquire(permits)
                repeat(permits) { requestTimes.add(testScheduler.currentTime) }
                checks[next.workKey] = (checks[next.workKey] ?: 0) + 1
                if (next.workKey == "partial") savedOffset += 630
                scheduler.finish(next, if (next.workKey == "partial") ArchiveSyncResult.Continue else ArchiveSyncResult.Complete)
            }
        }
        advanceTimeBy(30 * 60_000L)
        job.cancelAndJoin()
        assertEquals(547, checks.filterKeys { it != "partial" }.size)
        assertTrue(checks.filterKeys { it != "partial" }.values.all { it == 1 })
        assertTrue(checks.getValue("partial") > 1)
        assertTrue(savedOffset > 630)
        assertTrue(requestTimes.groupBy { it / 60_000 }.values.all { it.size <= 32 })
        assertEquals("continuations coalesce into one durable target row", 548, store.rows.size)
    }

    @Test fun restartAndUnchangedOrPresentationOnlyCatalogsPreserveCooldown() = runTest {
        val store = Store()
        var now = 1_000L
        val first = ArchiveSyncScheduler(store) { now }
        first.seed(work("old"), complete = false)
        val claimed = first.next("host", null)!!
        first.finish(claimed, ArchiveSyncResult.Complete)
        val due = store.rows.getValue("old").nextRunAt
        now += 60_000
        val restarted = ArchiveSyncScheduler(store) { now }
        repeat(100) { restarted.seed(work("old").copy(targetJson = "presentation title $it"), complete = true) }
        assertEquals(due, store.rows.getValue("old").nextRunAt)
        assertNull(restarted.next("host", null))
        now = due
        assertEquals("old", restarted.next("host", null)!!.workKey)
    }

    @Test fun aChangedSourceDuringARequestCannotBeOverwrittenByItsOldCompletion() = runTest {
        val store = Store()
        val scheduler = ArchiveSyncScheduler(store) { testScheduler.currentTime }
        scheduler.seed(work("target"), false)
        val old = scheduler.next("host", null)!!
        scheduler.seed(work("target", hint = "r2"), false)
        scheduler.finish(old, ArchiveSyncResult.Complete)
        assertEquals("r2", scheduler.next("host", null)!!.sourceHint)
        scheduler.invalidate("target")
        assertEquals(1, store.rows.size)
    }

    @Test fun switchingHostDoesNotDispatchAnotherHostsPendingWork() = runTest {
        val store = Store()
        val scheduler = ArchiveSyncScheduler(store) { testScheduler.currentTime }
        scheduler.seed(work("a", host = "a"), false)
        scheduler.seed(work("b", host = "b"), false)
        assertEquals("b", scheduler.next("b", null)!!.workKey)
        assertEquals("a", scheduler.next("a", null)!!.workKey)
        assertEquals(2, store.rows.size)
    }

    @Test fun retryDeadlinesSurviveRestartAndBackOffInsteadOfSpinning() = runTest {
        val store = Store()
        var now = 0L
        val scheduler = ArchiveSyncScheduler(store) { now }
        scheduler.seed(work("failed"), false)
        scheduler.finish(scheduler.next("host", null)!!, ArchiveSyncResult.Retry())
        assertEquals(60_000L, store.rows.getValue("failed").nextRunAt)
        val restarted = ArchiveSyncScheduler(store) { now }
        assertNull(restarted.next("host", null))
        now = 60_000
        restarted.finish(restarted.next("host", null)!!, ArchiveSyncResult.Retry())
        assertEquals(180_000L, store.rows.getValue("failed").nextRunAt)
    }

    @Test fun screenOffCannotResetAnAlreadyConsumedForegroundWindow() = runTest {
        var foreground = true
        val budget = ArchiveRequestBudget({ foreground }) { testScheduler.currentTime }
        repeat(40) { budget.acquire() }
        foreground = false
        var sent = false
        val waiting = launch { budget.acquire(); sent = true }
        runCurrent()
        advanceTimeBy(59_999)
        assertFalse(sent)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(sent)
        waiting.join()
        repeat(31) { budget.acquire() }
        assertEquals(60_000L, budget.availableInMillis())
    }
}
