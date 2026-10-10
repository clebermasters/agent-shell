package com.agentshell.data.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class ChatTarget(
    val hostId: String,
    val sessionName: String,
    val windowIndex: Int = 0,
    val isAcp: Boolean = false,
    val cwd: String = "",
    val title: String = "",
) {
    val key: String
        get() = chatFingerprint(listOf(hostId, isAcp.toString(), sessionName, windowIndex.toString()).joinToString("\u0000"))

    val displayName: String
        get() = title.ifBlank { sessionName } + if (!isAcp && windowIndex != 0) " · window $windowIndex" else ""

    companion object {
        fun create(hostId: String, sessionName: String, windowIndex: Int = 0, isAcp: Boolean = false, cwd: String = "", title: String = "") =
            ChatTarget(hostId, if (isAcp) sessionName.removePrefix("acp_") else sessionName, if (isAcp) 0 else windowIndex, isAcp, cwd, title)
    }
}

internal fun chatFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
data class ChatCursor(val id: String, val timestamp: Long, val preview: String? = null)

val ChatMessage.cursor: ChatCursor get() = ChatCursor(id, timestamp, if (id.startsWith("stream:")) content?.trim()?.take(80) else null)

val ChatMessage.isIncomingReply: Boolean
    get() = messageType == ChatMessageType.ASSISTANT &&
        (!content.isNullOrBlank() || blocks.any {
            (it.blockType == ChatBlockType.TEXT && !it.text.isNullOrBlank()) ||
                it.blockType in setOf(ChatBlockType.IMAGE, ChatBlockType.AUDIO, ChatBlockType.FILE)
        })

@Serializable
data class ChatReadState(
    val target: ChatTarget,
    val initialized: Boolean = false,
    val lastRead: ChatCursor? = null,
    val latestReceived: ChatCursor? = null,
    val unread: List<ChatCursor> = emptyList(),
    val seenIds: List<String> = emptyList(),
    val conversationKey: String = "",
) {
    /** A first history load is a baseline, rather than hundreds of new-message alerts. */
    fun reconcileHistory(messages: List<ChatMessage>): Pair<ChatReadState, List<ChatMessage>> {
        if (!initialized) {
            return copy(
                initialized = true,
                lastRead = if (unread.isEmpty()) messages.lastOrNull()?.cursor else lastRead,
                latestReceived = messages.lastOrNull()?.cursor ?: latestReceived,
                seenIds = (seenIds + messages.map { it.id }).distinct().takeLast(256),
            ).normalizeStreamingUnread(messages.filter { it.isIncomingReply }) to emptyList()
        }
        val latest = latestReceived
        val anchor = messages.indexOfFirst { it.id == latest?.id }
        val candidates = when {
            latest == null -> messages
            anchor >= 0 -> messages.drop(anchor + 1)
            latest.timestamp > 0 -> messages.filter { it.timestamp > latest.timestamp }
            else -> emptyList()
        }
        var updated = this
        val incoming = mutableListOf<ChatMessage>()
        for (message in candidates) {
            val (next, added) = updated.receive(message)
            updated = next
            if (added) incoming.add(message)
        }
        val streamCursors = unread.filter { it.id.startsWith("stream:") }
        updated = updated.normalizeStreamingUnread(incoming.ifEmpty { messages.filter { it.isIncomingReply } })
        val matchingStreamIds = incoming.filter { reply -> streamCursors.any { cursor ->
            !cursor.preview.isNullOrBlank() && reply.content?.trim()?.startsWith(cursor.preview) == true
        } }.mapTo(mutableSetOf()) { it.id }
        return updated to incoming.filterNot { it.id in matchingStreamIds }
    }

    private fun normalizeStreamingUnread(replies: List<ChatMessage>): ChatReadState {
        val streamCursors = unread.filter { it.id.startsWith("stream:") }
        if (streamCursors.isEmpty() || replies.isEmpty()) return this
        val replacements = streamCursors.mapNotNull { old ->
            replies.lastOrNull { reply -> !old.preview.isNullOrBlank() && reply.content?.trim()?.startsWith(old.preview) == true }
                ?.let { old.id to it.cursor }
        }.toMap()
        return copy(unread = unread.map { replacements[it.id] ?: it }.distinctBy { it.id })
    }

    /** Returns true only for a new user-visible reply, never for a replay or tool/thinking event. */
    fun receive(message: ChatMessage): Pair<ChatReadState, Boolean> {
        if (message.id in seenIds) return this to false
        val incoming = message.isIncomingReply
        return copy(
            latestReceived = if (latestReceived == null || message.timestamp >= latestReceived.timestamp) message.cursor else latestReceived,
            seenIds = (seenIds + message.id).takeLast(256),
            unread = if (incoming) unread + message.cursor else unread,
        ) to incoming
    }

    fun readThrough(visiblePrefix: List<ChatMessage>): ChatReadState {
        val last = visiblePrefix.lastOrNull()?.cursor ?: return this
        val ids = visiblePrefix.mapTo(mutableSetOf()) { it.id }
        val advance = lastRead == null || lastRead.id in ids || last.timestamp > lastRead.timestamp
        return copy(
            lastRead = if (advance) last else lastRead,
            unread = unread.filterNot {
                it.id in ids || (it.timestamp > 0 && it.timestamp < last.timestamp) ||
                    (it.id.startsWith("stream:") && it.timestamp <= last.timestamp)
            },
        )
    }

    /** Older pages can fill a reconnect gap without generating historical notification sounds. */
    fun includeOlderUnread(messages: List<ChatMessage>): ChatReadState {
        if (unread.isEmpty() || lastRead == null) return this
        val olderUnread = messages.filter { it.isIncomingReply && it.timestamp > lastRead.timestamp }
            .map { it.cursor }
        return copy(unread = (unread + olderUnread).distinctBy { it.id }.sortedBy { it.timestamp })
    }

    /** Finds the beginning of the unread section, including after history IDs change during streaming. */
    fun unreadIndex(messages: List<ChatMessage>, hasMore: Boolean): Int? {
        val first = unread.firstOrNull() ?: return null
        val exact = messages.indexOfFirst { it.id == first.id }
        if (exact >= 0) return exact
        if (!first.preview.isNullOrBlank()) {
            val streamedReply = messages.indexOfFirst { it.isIncomingReply && it.content?.trim()?.startsWith(first.preview) == true }
            if (streamedReply >= 0) return streamedReply
        }
        if (hasMore && first.timestamp > 0 && messages.firstOrNull()?.timestamp?.let { it > first.timestamp } == true) return null
        val readIndex = messages.indexOfFirst { it.id == lastRead?.id }
        if (readIndex >= 0) return (readIndex + 1).takeIf { it < messages.size }
        val byTimestamp = messages.indexOfFirst { it.isIncomingReply && (first.timestamp == 0L || it.timestamp >= first.timestamp) }
        if (byTimestamp >= 0) return byTimestamp
        return if (!hasMore) messages.indexOfFirst { it.isIncomingReply }.takeIf { it >= 0 } else null
    }
}
