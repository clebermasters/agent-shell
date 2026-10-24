package com.agentshell.feature.chat

import androidx.compose.runtime.mutableStateListOf
import com.agentshell.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatMessageRowsTest {
    private fun message(id: String) = ChatMessage(id, "assistant", id, 1)

    @Test fun previousLazyProviderRemainsReadableWhenRefreshClearsHistory() {
        val source = mutableStateListOf(message("first"), message("last"))
        val rows = ChatMessageRows(source, null)
        val oldKeys = (0 until rows.count).map { index -> { rows.key(index) } }
        val oldContent = (0 until rows.count).map { index -> { rows.message(index) } }

        source.clear()

        assertEquals(listOf("first", "last"), oldKeys.map { it() })
        assertEquals(listOf("first", "last"), oldContent.map { it().id })
        assertEquals(0, ChatMessageRows(source, null).count)
    }

    @Test fun unreadDividerAndContentUseTheSameHistoryVersionAfterShorterRefresh() {
        val source = mutableStateListOf(message("read"), message("unread"), message("last"))
        val previous = ChatMessageRows(source, "unread")
        source.clear()
        source.add(message("replacement"))

        assertEquals(listOf("read", ChatListAnchor.Unread, "unread", "last"), (0 until previous.count).map(previous::key))
        assertEquals(listOf("read", "unread", "last"), (0 until previous.count).filterNot(previous::isUnread).map { previous.message(it).id })
        val current = ChatMessageRows(source, "unread")
        assertEquals(1, current.count)
        assertEquals("replacement", current.key(0))
    }

    @Test fun repeatedClearAppendAndConversationSwitchKeepEveryCapturedProviderValid() {
        val source = mutableStateListOf<ChatMessage>()
        val captured = mutableListOf<Pair<ChatMessageRows, List<Any>>>()
        repeat(100) { turn ->
            source.clear()
            source.addAll((0 until turn % 8).map { message("$turn:$it") })
            val rows = ChatMessageRows(source, source.lastOrNull()?.id)
            captured.add(rows to (0 until rows.count).map(rows::key))
        }
        source.clear()
        for ((rows, keys) in captured) {
            assertEquals(keys, (0 until rows.count).map(rows::key))
            (0 until rows.count).filterNot(rows::isUnread).forEach { assertEquals(rows.key(it), rows.message(it).id) }
        }
    }

    @Test fun absentUnreadBoundaryDoesNotReadAnyMessages() {
        val unreadArchive = object : AbstractList<ChatMessage>() {
            override val size = 100_000
            override fun get(index: Int): ChatMessage = error("Layout scanned the archive")
        }
        repeat(100) { assertEquals(-1, chatUnreadIndex(unreadArchive, null)) }
    }

    @Test fun viewportReadTrackingUsesOnlyVisibleKeysAndExcludesLayoutSentinels() {
        assertEquals("last-visible", lastVisibleChatMessageKey(listOf("first", ChatListAnchor.Unread, "last-visible", ChatListAnchor.Streaming, ChatListAnchor.End)))
        assertNull(lastVisibleChatMessageKey(listOf(ChatListAnchor.Unread, ChatListAnchor.End)))
    }

}
