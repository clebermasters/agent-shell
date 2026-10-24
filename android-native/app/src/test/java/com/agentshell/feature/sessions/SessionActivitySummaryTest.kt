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

    @Test fun unreadCompletionRemainsVisibleAboveWorkInAnotherWindowUntilAcknowledged() {
        val otherWindow = target.copy(windowIndex = 2)
        val completion = AgentCompletionReceipt(target, "finished:1", 9_000)
        val states = listOf(target, otherWindow).associate { it.key to ChatReadState(it) }
        val activities = mapOf(otherWindow.key to working)
        val unread = summarizeSessionActivities(target.hostId, activities, states, mapOf(target.key to completion), 11_000)
        assertTrue(unread.tmux.getValue(target.sessionName).unreadCompletion)
        assertEquals(1, unread.finishedWindows[target.sessionName])
        val acknowledged = summarizeSessionActivities(target.hostId, activities, states, mapOf(target.key to completion.copy(acknowledged = true)), 11_000)
        assertEquals(AgentActivityStatus.WORKING, acknowledged.tmux.getValue(target.sessionName).status)
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
        assertTrue(saved.tmux.getValue(target.sessionName).unreadCompletion)
    }
}
