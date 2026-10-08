package com.agentshell.data.model

import org.junit.Assert.*
import org.junit.Test

class AgentActivityTest {
    private fun snapshot(status: String = "working", sequence: Long = 1, observer: String = "pane-one", observed: Long = 120_000): Map<String, Any?> = mapOf(
        "type" to "chat-activity", "sessionName" to "session", "windowIndex" to 1, "paneId" to "%2",
        "state" to mapOf("status" to status, "detail" to "Agent is working", "source" to "codex-log", "confidence" to "reported", "startedAt" to 100_000, "observedAt" to observed, "observerId" to observer, "sequence" to sequence),
    )
    @Test fun parsesRuntimeSnapshotsWithoutPersistingThem() {
        val state = AgentActivity.parse(snapshot(), 1000)!!
        assertEquals(AgentActivityStatus.WORKING, state.status)
        assertEquals("%2", state.paneId)
        assertTrue(state.backendSnapshot)
    }
    @Test fun elapsedUsesServerDurationAndLocalMonotonicTimeDespiteClockSkew() {
        val state = AgentActivity.parse(snapshot(), 1000)!!
        assertEquals(25L, state.elapsedSeconds(6000))
    }
    @Test fun staleWorkingStateFallsBackToUnknownRatherThanIdle() {
        val state = AgentActivity.parse(snapshot(), 1000)!!
        assertFalse(state.expired(15_000))
        assertTrue(state.expired(17_000))
        val expired = state.unknown("Live status is unavailable")
        assertEquals(AgentActivityStatus.UNKNOWN, expired.status)
        assertNull(expired.startedAt)
    }
    @Test fun ignoresLateSnapshotsFromTheSameObserver() {
        val current = AgentActivity.parse(snapshot(sequence = 10), 1000)!!
        assertFalse(current.accepts(AgentActivity.parse(snapshot(sequence = 9), 2000)!!))
        assertFalse(current.accepts(AgentActivity.parse(snapshot(sequence = 10, observed = 119_000), 2000)!!))
        assertTrue(current.accepts(AgentActivity.parse(snapshot(sequence = 11), 2000)!!))
    }
    @Test fun acceptsAFreshObserverAfterBackendRestart() {
        val current = AgentActivity.parse(snapshot(sequence = 10), 1000)!!
        assertTrue(current.accepts(AgentActivity.parse(snapshot(sequence = 1, observer = "new-pane", observed = 121_000), 2000)!!))
        assertFalse(current.accepts(AgentActivity.parse(snapshot(sequence = 1, observer = "older-pane", observed = 119_000), 2000)!!))
    }
    @Test fun unknownFutureStatusesRemainUnknown() {
        assertEquals(AgentActivityStatus.UNKNOWN, AgentActivity.parse(snapshot(status = "new-status"), 1000)!!.status)
        assertNull(AgentActivity.parse(mapOf("state" to emptyMap<String, Any?>()), 1000))
    }
}
