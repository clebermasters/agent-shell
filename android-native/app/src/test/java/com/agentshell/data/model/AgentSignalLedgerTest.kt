package com.agentshell.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentSignalLedgerTest {
    private fun working(id: String = "one") = AgentActivity(status = AgentActivityStatus.WORKING, source = "codex-log", confidence = "reported", turnId = id, startedAt = 1000)
    private fun finished(id: String = "one", reason: String = "completed") = AgentActivity(status = if (reason == "failed") AgentActivityStatus.FAILED else AgentActivityStatus.IDLE, source = "codex-log", confidence = "reported", turnId = id, finishedAt = 61_000, completionReason = reason)

    @Test fun aCompletedTurnProducesOneAlertAndDuration() {
        val armed = AgentSignalLedger().observe(working()).first
        val (done, signal) = armed.observe(finished())
        assertEquals(AgentSignalKind.FINISHED, signal!!.kind)
        assertEquals(60L, signal.durationSeconds)
        assertNull(done.observe(finished()).second)
    }
    @Test fun firstIdleSnapshotIsABaselineRatherThanAnAlert() {
        val baseline = AgentSignalLedger().observe(finished())
        assertNull(baseline.second)
        assertNull(baseline.first.observe(finished()).second)
    }
    @Test fun receiptsSurviveRestartAndDoNotRearmFromLateWorkingPackets() {
        val done = AgentSignalLedger().observe(working()).first.observe(finished()).first
        val restored = Json.decodeFromString<AgentSignalLedger>(Json.encodeToString(done))
        val late = restored.observe(working()).first
        assertNull(late.observe(finished()).second)
    }
    @Test fun completionDuringDisconnectIsRecoveredOnce() {
        val armed = AgentSignalLedger().observe(working()).first.observe(AgentActivity().unknown("Disconnected")).first
        val restored = Json.decodeFromString<AgentSignalLedger>(Json.encodeToString(armed))
        assertEquals(AgentSignalKind.FINISHED, restored.observe(finished()).second!!.kind)
    }
    @Test fun cancelledFailedAndDifferentTurnsDoNotAnnounceSuccess() {
        for (event in listOf(finished(reason = "interrupted"), finished(reason = "failed"), finished(id = "other"))) {
            assertNull(AgentSignalLedger().observe(working()).first.observe(event).second)
        }
    }
    @Test fun anEstimatedIdleStateCannotAnnounceConfirmedCompletion() {
        val idle = AgentActivity(status = AgentActivityStatus.IDLE, source = "tmux-status", confidence = "inferred")
        assertNull(AgentSignalLedger().observe(working()).first.observe(idle).second)
    }
    @Test fun quietAlertsFireOnlyAfterActivityAndRearmOnANewBurst() {
        val recent = AgentActivity(status = AgentActivityStatus.RECENT_ACTIVITY, source = "tmux-activity", confidence = "estimated", lastActivityAt = 1000)
        val quiet = AgentActivity(status = AgentActivityStatus.UNKNOWN, source = "tmux-activity", confidence = "estimated", quietAt = 11_000)
        assertNull(AgentSignalLedger().observe(quiet).second)
        val (done, signal) = AgentSignalLedger().observe(recent).first.observe(quiet)
        assertEquals(AgentSignalKind.QUIET, signal!!.kind)
        assertNull(done.observe(quiet).second)
        val rearmed = done.observe(recent.copy(lastActivityAt = 20_000)).first
        assertEquals(AgentSignalKind.QUIET, rearmed.observe(quiet.copy(quietAt = 30_000)).second!!.kind)
    }
    @Test fun lostConnectionsAndCopyModeDoNotBecomeQuietAlerts() {
        val recent = AgentActivity(status = AgentActivityStatus.RECENT_ACTIVITY, source = "tmux-activity")
        val armed = AgentSignalLedger().observe(recent).first
        assertNull(armed.observe(AgentActivity().unknown("Disconnected")).second)
        assertNull(armed.observe(AgentActivity(status = AgentActivityStatus.UNKNOWN, source = "tmux-activity", detail = "Copy mode")).second)
    }
    @Test fun anIdleTerminalCanAlsoConfirmAQuietTransition() {
        val recent = AgentActivity(status = AgentActivityStatus.RECENT_ACTIVITY, source = "tmux-activity")
        val idle = AgentActivity(status = AgentActivityStatus.IDLE, source = "tmux-status", quietAt = 20_000)
        assertEquals(AgentSignalKind.QUIET, AgentSignalLedger().observe(recent).first.observe(idle).second!!.kind)
    }
}
