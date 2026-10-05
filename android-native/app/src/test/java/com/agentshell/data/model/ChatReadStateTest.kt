package com.agentshell.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatReadStateTest {
    private val target = ChatTarget.create("server-a", "my session", 1)
    private fun reply(id: String, time: Long) = ChatMessage(id, "assistant", "Reply $id", time)
    private val old = reply("old", 100)

    @Test fun firstHistoryIsABaselineWithoutNotifications() {
        val (state, incoming) = ChatReadState(target).reconcileHistory(listOf(old))
        assertTrue(state.initialized)
        assertEquals(old.cursor, state.lastRead)
        assertTrue(state.unread.isEmpty())
        assertTrue(incoming.isEmpty())
    }

    @Test fun replayingALiveMessageDoesNotNotifyTwice() {
        val baseline = ChatReadState(target).reconcileHistory(listOf(old)).first
        val new = reply("new", 200)
        val (unread, shouldNotify) = baseline.receive(new)
        assertTrue(shouldNotify)
        val (replayed, notifyAgain) = unread.receive(new)
        assertFalse(notifyAgain)
        assertEquals(unread, replayed)
        assertEquals(listOf(new.cursor), replayed.unread)
    }

    @Test fun reconnectOnlyAddsRepliesAfterTheLastReceivedMessage() {
        val baseline = ChatReadState(target).reconcileHistory(listOf(old)).first
        val missed = reply("missed", 200)
        val (state, incoming) = baseline.reconcileHistory(listOf(old, missed))
        assertEquals(listOf(missed), incoming)
        assertEquals(listOf(missed.cursor), state.unread)
        assertTrue(state.reconcileHistory(listOf(old, missed)).second.isEmpty())
    }

    @Test fun sendingMessagesAndReceivingToolsOrThinkingDoesNotAlert() {
        val messages = listOf(
            ChatMessage("user", "user", "Hello", 200),
            ChatMessage("tool", "tool_call", "Running command", 300),
            ChatMessage("thinking", "assistant", null, 400, blocks = listOf(ChatBlock("thinking", content = "Thinking"))),
        )
        var state = ChatReadState(target)
        for (message in messages) {
            val result = state.receive(message)
            state = result.first
            assertFalse(result.second)
        }
        assertTrue(state.unread.isEmpty())
    }

    @Test fun attachmentsAreIncomingReplies() {
        val attachment = ChatMessage("image", "assistant", null, 200, blocks = listOf(ChatBlock("image", id = "file-1")))
        assertTrue(ChatReadState(target).receive(attachment).second)
    }

    @Test fun readingTheFirstReplyLeavesTheNextReplyUnread() {
        val one = reply("one", 200)
        val two = reply("two", 300)
        val state = ChatReadState(target).reconcileHistory(listOf(old)).first.receive(one).first.receive(two).first
        val partial = state.readThrough(listOf(old, one))
        assertEquals(one.cursor, partial.lastRead)
        assertEquals(listOf(two.cursor), partial.unread)
        assertEquals(2, partial.unreadIndex(listOf(old, one, two), false))
        assertTrue(partial.readThrough(listOf(old, one, two)).unread.isEmpty())
    }

    @Test fun scrollingBackDoesNotMoveTheReadPositionBackward() {
        val one = reply("one", 200)
        val state = ChatReadState(target).reconcileHistory(listOf(old, one)).first
        assertEquals(one.cursor, state.readThrough(listOf(old)).lastRead)
    }

    @Test fun unreadBoundarySurvivesSerializationAndRestart() {
        val new = reply("new", 200)
        val original = ChatReadState(target).reconcileHistory(listOf(old)).first.receive(new).first
        val restored = Json.decodeFromString<ChatReadState>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(1, restored.unreadIndex(listOf(old, new), false))
        assertTrue(restored.reconcileHistory(listOf(old, new)).second.isEmpty())
    }

    @Test fun olderPaginationIsNotANewReplyAndAnUnloadedBoundaryWaitsForMoreHistory() {
        val firstUnread = reply("unread", 200)
        val newest = reply("newest", 500)
        val state = ChatReadState(target).reconcileHistory(listOf(old)).first.receive(firstUnread).first.receive(newest).first
        assertNull(state.unreadIndex(listOf(newest), hasMore = true))
        assertEquals(1, state.unreadIndex(listOf(old, firstUnread, newest), hasMore = false))
        assertTrue(state.reconcileHistory(listOf(reply("older", 50), old)).second.isEmpty())
    }

    @Test fun streamingCursorIsMatchedToTheDurableReplyWithoutAnotherAlert() {
        val baseline = ChatReadState(target).reconcileHistory(listOf(old)).first
        val stream = ChatMessage("stream:turn:0", "assistant", "The result is", 230)
        val pending = baseline.receive(stream).first.copy(latestReceived = baseline.latestReceived)
        val completed = ChatMessage("durable", "assistant", "The result is ready", 210)
        val (restored, incoming) = pending.reconcileHistory(listOf(old, completed))
        assertEquals(listOf(completed.cursor), restored.unread)
        assertTrue(incoming.isEmpty())
        assertTrue(restored.readThrough(listOf(old, completed)).unread.isEmpty())
    }

    @Test fun reconnectGapUsesOlderPagesToFindTheActualFirstUnreadReply() {
        val firstMissed = reply("first-missed", 200)
        val newest = reply("newest", 500)
        val fromLimitedHistory = ChatReadState(target).reconcileHistory(listOf(old)).first.reconcileHistory(listOf(newest)).first
        val complete = fromLimitedHistory.includeOlderUnread(listOf(old, firstMissed))
        assertEquals(listOf(firstMissed.cursor, newest.cursor), complete.unread)
        assertEquals(1, complete.unreadIndex(listOf(old, firstMissed, newest), false))
        assertEquals(newest.cursor, complete.latestReceived)
    }

    @Test fun historicalReplayDoesNotMoveAStreamingMarkerOntoAnOldReply() {
        val baseline = ChatReadState(target).reconcileHistory(listOf(old)).first
        val stream = ChatMessage("stream:turn:0", "assistant", "A new response", 200)
        val pending = baseline.receive(stream).first.copy(latestReceived = baseline.latestReceived)
        assertEquals(stream.cursor, pending.reconcileHistory(listOf(old)).first.unread.single())
    }

    @Test fun chatIdentitySeparatesHostsWindowsAndProviders() {
        val targets = listOf(target, target.copy(hostId = "server-b"), target.copy(windowIndex = 2), target.copy(isAcp = true))
        assertEquals(4, targets.map { it.key }.toSet().size)
        assertEquals(ChatTarget.create("server-a", "codex:abc", isAcp = true).key, ChatTarget.create("server-a", "acp_codex:abc", isAcp = true).key)
    }

    @Test fun parserProducesStableHistoryAndLiveIdsAndHonorsServerIds() {
        val raw = mapOf("role" to "assistant", "timestamp" to "2026-10-05T14:00:00Z", "blocks" to listOf(mapOf("type" to "text", "text" to "Hello")))
        val history = ChatMessageParser.parse(raw)
        val live = ChatMessageParser.parse(raw.toMap())
        assertEquals(history.id, live.id)
        assertEquals("Hello", history.content)
        assertEquals("server-id", ChatMessageParser.parse(raw + ("id" to "server-id")).id)
        assertNotEquals(history.id, ChatMessageParser.parse(raw + ("timestamp" to "2026-10-05T14:00:01Z")).id)
    }
}
