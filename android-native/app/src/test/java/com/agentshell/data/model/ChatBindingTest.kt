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
}
