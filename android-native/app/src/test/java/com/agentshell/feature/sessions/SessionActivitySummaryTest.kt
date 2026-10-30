package com.agentshell.feature.sessions

import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.AgentActivityStatus
import com.agentshell.data.model.AgentCompletionReceipt
import com.agentshell.data.model.ChatReadState
import com.agentshell.data.model.ChatTarget
import org.junit.Assert.*
import org.junit.Test

class SessionActivitySummaryTest {
    private val target = ChatTarget.create("server-a", "session", 1)
    private val working = AgentActivity(status = AgentActivityStatus.WORKING, receivedAt = 10_000)

    @Test fun workInAnotherWindowStaysLiveWhileCompletionRemainsUnread() {
        val otherWindow = target.copy(windowIndex = 2)
        val completion = AgentCompletionReceipt(target, "finished:1", 9_000)
        val states = listOf(target, otherWindow).associate { it.key to ChatReadState(it) }
        val activities = mapOf(otherWindow.key to working)
        val unread = summarizeSessionActivities(target.hostId, activities, states, mapOf(target.key to completion), 11_000)
        assertEquals(AgentActivityStatus.WORKING, unread.tmux.getValue(target.sessionName).status)
        assertTrue(unread.tmux.getValue(target.sessionName).unreadCompletion)
        assertEquals(1, unread.finishedWindows[target.sessionName])
        val acknowledged = summarizeSessionActivities(target.hostId, activities, states, mapOf(target.key to completion.copy(acknowledged = true)), 11_000)
        assertEquals(AgentActivityStatus.WORKING, acknowledged.tmux.getValue(target.sessionName).status)
        assertFalse(acknowledged.tmux.getValue(target.sessionName).unreadCompletion)
        assertTrue(acknowledged.finishedWindows.isEmpty())
    }

    @Test fun newestUnreadWindowAndRestoredDirectCompletionAreScopedToSelectedHost() {
        val laterWindow = target.copy(windowIndex = 3)
        val direct = ChatTarget.create(target.hostId, "codex:direct", isAcp = true)
        val foreign = target.copy(hostId = "server-b")
        val receipts = listOf(
            AgentCompletionReceipt(target, "old", 1),
            AgentCompletionReceipt(laterWindow, "new", 2),
            AgentCompletionReceipt(direct, "direct", 3),
            AgentCompletionReceipt(foreign, "foreign", 4),
        ).associateBy { it.target.key }
        val summary = summarizeSessionActivities(target.hostId, emptyMap(), emptyMap(), receipts, 100)
        assertEquals(mapOf(target.sessionName to 3), summary.finishedWindows)
        assertTrue(summary.direct.getValue(direct.sessionName).unreadCompletion)
        assertEquals(1, summary.tmux.size)
    }

    @Test fun liveActivityExpiresWhileSavedCompletionDoesNot() {
        val completion = AgentCompletionReceipt(target, "finished", 1)
        val states = mapOf(target.key to ChatReadState(target))
        val expired = summarizeSessionActivities(target.hostId, mapOf(target.key to working), states, emptyMap(), 100_000)
        assertEquals(AgentActivityStatus.UNKNOWN, expired.tmux.getValue(target.sessionName).status)
        val saved = summarizeSessionActivities(target.hostId, mapOf(target.key to working), states, mapOf(target.key to completion), 100_000)
        assertEquals(AgentActivityStatus.UNKNOWN, saved.tmux.getValue(target.sessionName).status)
        assertTrue(saved.tmux.getValue(target.sessionName).unreadCompletion)
    }

    @Test fun previousCompletionCannotHideANewRunInTheSameWindow() {
        val completion = AgentCompletionReceipt(target, "previous-run", 9_000)
        val states = mapOf(target.key to ChatReadState(target))
        val summary = summarizeSessionActivities(target.hostId, mapOf(target.key to working), states, mapOf(target.key to completion), 11_000)

        assertEquals(AgentActivityStatus.WORKING, summary.tmux.getValue(target.sessionName).status)
        assertTrue(summary.tmux.getValue(target.sessionName).unreadCompletion)
        assertFalse(completion.acknowledged)
        assertEquals(target.windowIndex, summary.finishedWindows[target.sessionName])
    }

    @Test fun pendingCompletionDoesNotOverrideWaitingRecentActivityOrFailure() {
        val completion = AgentCompletionReceipt(target, "previous-run", 9_000)
        val states = mapOf(target.key to ChatReadState(target))
        for (status in listOf(AgentActivityStatus.WAITING, AgentActivityStatus.RECENT_ACTIVITY, AgentActivityStatus.FAILED)) {
            val live = working.copy(status = status)
            val summary = summarizeSessionActivities(target.hostId, mapOf(target.key to live), states, mapOf(target.key to completion), 11_000)
            assertEquals(status, summary.tmux.getValue(target.sessionName).status)
            assertTrue(summary.tmux.getValue(target.sessionName).unreadCompletion)
        }
    }

    @Test fun directSessionStatusUpdatesWithoutOpeningChatToAcknowledgePreviousRun() {
        val direct = ChatTarget.create(target.hostId, "codex:agent", isAcp = true)
        val completion = AgentCompletionReceipt(direct, "previous-run", 9_000)
        val states = mapOf(direct.key to ChatReadState(direct))
        val running = summarizeSessionActivities(direct.hostId, mapOf(direct.key to working), states, mapOf(direct.key to completion), 11_000)
        assertEquals(AgentActivityStatus.WORKING, running.direct.getValue(direct.sessionName).status)
        assertTrue(running.direct.getValue(direct.sessionName).unreadCompletion)

        val finished = summarizeSessionActivities(direct.hostId, mapOf(direct.key to working.copy(status = AgentActivityStatus.IDLE)), states,
            mapOf(direct.key to AgentCompletionReceipt(direct, "current-run", 11_000)), 11_000)
        assertEquals(AgentActivityStatus.IDLE, finished.direct.getValue(direct.sessionName).status)
        assertTrue(finished.direct.getValue(direct.sessionName).unreadCompletion)
        assertEquals(11_000L, finished.direct.getValue(direct.sessionName).finishedAt)
    }

    @Test fun openingChatOnlyAcknowledgesTheReminderAndDoesNotChangeLiveWork() {
        val completion = AgentCompletionReceipt(target, "previous-run", 9_000)
        val states = mapOf(target.key to ChatReadState(target))
        val before = summarizeSessionActivities(target.hostId, mapOf(target.key to working), states, mapOf(target.key to completion), 11_000)
        val after = summarizeSessionActivities(target.hostId, mapOf(target.key to working), states,
            mapOf(target.key to completion.copy(acknowledged = true)), 11_000)
        assertEquals(before.tmux.getValue(target.sessionName).status, after.tmux.getValue(target.sessionName).status)
        assertEquals(AgentActivityStatus.WORKING, after.tmux.getValue(target.sessionName).status)
    }
}
