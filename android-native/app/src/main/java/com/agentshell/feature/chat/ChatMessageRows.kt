package com.agentshell.feature.chat

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.agentshell.data.model.ChatMessage

/** A lazy layout may still use its previous provider after the live history changes. */
internal class ChatMessageRows(source: SnapshotStateList<ChatMessage>, firstUnreadId: String?) {
    // SnapshotStateList.toList() is an immutable, O(1) snapshot. Count, keys, and
    // content must all refer to this same version of the messages.
    private val messages = source.toList()
    private val unreadIndex = chatUnreadIndex(messages, firstUnreadId)
    private val hasUnread = unreadIndex >= 0
    val count = messages.size + if (hasUnread) 1 else 0

    fun isUnread(index: Int) = hasUnread && index == unreadIndex

    fun key(index: Int): Any = if (isUnread(index)) ChatListAnchor.Unread else message(index).id

    fun message(index: Int): ChatMessage = messages[index - if (hasUnread && index > unreadIndex) 1 else 0]
}

/** A missing unread marker must not scan an unlimited archive on each layout pass. */
internal fun chatUnreadIndex(messages: List<ChatMessage>, unreadId: String?): Int =
    unreadId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1

internal fun lastVisibleChatMessageKey(keys: List<Any>): String? = keys.lastOrNull { it is String } as? String
