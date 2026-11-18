package com.agentshell.data.model

import org.junit.Assert.*
import org.junit.Test

class ChatBindingTest {
    private fun bound(id: String, key: String) = ChatBindingState.parse(mapOf("state" to mapOf(
        "status" to "bound", "paneToken" to "pane-token", "tool" to "codex",
        "binding" to mapOf("bindingId" to id, "conversationId" to "native-$id", "conversationKey" to key, "source" to "hook"),
    )))!!

    @Test fun packetsStayWithTheirIndependentConversationEvenWhenFoldersAreShared() {
        val first = bound("first", "conversation:codex:first")
        val second = bound("second", "conversation:codex:second")
        val message = mapOf<String, Any?>("type" to "chat-event", "bindingId" to "first", "conversationKey" to "conversation:codex:first")
        assertTrue(first.acceptsConversationPacket(message))
        assertFalse(second.acceptsConversationPacket(message))
    }

    @Test fun resumingAndRestartingRejectPacketsFromThePreviousLease() {
        val previous = bound("old", "conversation:codex:one")
        val restarted = bound("new", "conversation:codex:one")
        assertFalse(restarted.acceptsConversationPacket(mapOf("bindingId" to previous.binding!!.id)))
        assertTrue(restarted.acceptsConversationPacket(mapOf("bindingId" to restarted.binding!!.id)))
    }

    @Test fun unlinkedChatsNeverAcceptTaggedHistoryOrEnableSend() {
        val unlinked = ChatBindingState.parse(mapOf("state" to mapOf("status" to "required", "paneToken" to "token",
            "tool" to "claude", "binding" to null, "candidates" to listOf(mapOf("id" to "a", "title" to "A", "updatedAt" to 123L)))))!!
        assertFalse(unlinked.canSend)
        assertFalse(unlinked.acceptsConversationPacket(mapOf("bindingId" to "old")))
        assertEquals("A", unlinked.candidates.single().title)
    }

    @Test fun directSessionsAndOlderServerPacketsRemainCompatible() {
        val noBinding: ChatBindingState? = null
        assertTrue(noBinding.acceptsConversationPacket(mapOf("type" to "acp-message-chunk", "sessionId" to "one")))
        assertTrue(noBinding.acceptsConversationPacket(mapOf("type" to "chat-history")))
    }

    @Test fun currentBoundConversationAcceptsFilesAfterSessionRenameAndWindowMove() {
        val current = bound("lease", "conversation:codex:one")
        val packet = mapOf<String, Any?>("type" to "chat-event", "sessionName" to "renamed session", "windowIndex" to 7,
            "bindingId" to "lease", "conversationKey" to "conversation:codex:one")
        assertTrue(current.matchesConversationTarget(packet, "old session", 0))
        assertFalse(bound("other", "conversation:codex:other").matchesConversationTarget(packet, "renamed session", 7))
        assertFalse(current.matchesConversationTarget(packet + ("conversationKey" to "conversation:codex:other"), "old session", 0))
        assertFalse(current.matchesConversationTarget(packet + ("bindingId" to "old-lease"), "renamed session", 7))
    }

    @Test fun legacyPacketsStillNeedTheExactSessionAndWindow() {
        val current = bound("lease", "conversation:codex:one")
        val packet = mapOf<String, Any?>("type" to "chat-file-message", "sessionName" to "session", "windowIndex" to 2)
        assertTrue(current.matchesConversationTarget(packet, "session", 2))
        assertFalse(current.matchesConversationTarget(packet, "other", 2))
        assertFalse(current.matchesConversationTarget(packet, "session", 0))
        assertFalse(current.matchesConversationTarget(mapOf("sessionName" to "session"), "session", 2))
        assertFalse(current.acceptsConversationPacket(packet + ("bindingId" to 42)))
    }

    @Test fun directSessionPrefixCompatibilityDoesNotAcceptTerminalLeases() {
        val noBinding: ChatBindingState? = null
        val packet = mapOf<String, Any?>("sessionName" to "acp_codex:direct", "windowIndex" to 0)
        assertTrue(noBinding.matchesConversationTarget(packet, "codex:direct", 0, isAcp = true))
        assertFalse(noBinding.matchesConversationTarget(packet, "other", 0, isAcp = true))
        assertFalse(bound("lease", "one").matchesConversationTarget(packet + ("bindingId" to "lease"), "codex:direct", 0, isAcp = true))
    }
}
