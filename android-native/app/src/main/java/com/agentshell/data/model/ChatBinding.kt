package com.agentshell.data.model

data class ConversationCandidate(val id: String, val title: String, val updatedAt: Long)
data class ConversationBinding(val id: String, val conversationId: String, val conversationKey: String, val source: String)
data class ChatBindingState(
    val status: String,
    val paneToken: String,
    val tool: String,
    val binding: ConversationBinding?,
    val candidates: List<ConversationCandidate>,
    val detail: String,
) {
    val canSend get() = status == "bound" && binding != null

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(message: Map<String, Any?>): ChatBindingState? {
            val state = message["state"] as? Map<String, Any?> ?: return null
            val binding = (state["binding"] as? Map<String, Any?>)?.let {
                val id = it["bindingId"] as? String ?: return@let null
                val conversation = it["conversationId"] as? String ?: return@let null
                val key = it["conversationKey"] as? String ?: return@let null
                ConversationBinding(id, conversation, key, it["source"] as? String ?: "")
            }
            val candidates = (state["candidates"] as? List<*>)?.mapNotNull {
                val row = it as? Map<String, Any?> ?: return@mapNotNull null
                val id = row["id"] as? String ?: return@mapNotNull null
                ConversationCandidate(id, (row["title"] as? String).orEmpty().ifBlank { "Conversation ${id.take(8)}" }, (row["updatedAt"] as? Number)?.toLong() ?: 0)
            }.orEmpty()
            return ChatBindingState(state["status"] as? String ?: "unavailable", state["paneToken"] as? String ?: "", state["tool"] as? String ?: "", binding, candidates, state["detail"] as? String ?: "")
        }
    }
}

/** A delayed packet from a previous conversation must never be shown after a link changes. */
fun ChatBindingState?.acceptsConversationPacket(message: Map<String, Any?>): Boolean {
    val packet = message["bindingId"] ?: return true // Direct sessions and older backends.
    val current = this?.takeIf { it.canSend }?.binding ?: return false
    if (packet !is String || current.id != packet) return false
    val conversationKey = message["conversationKey"]
    return conversationKey == null || conversationKey == current.conversationKey
}

/** Session/window aliases may change while the verified native conversation stays open. */
fun ChatBindingState?.matchesConversationTarget(
    message: Map<String, Any?>,
    sessionName: String,
    windowIndex: Int,
    isAcp: Boolean = false,
): Boolean {
    if (message["bindingId"] != null) {
        // Direct sessions do not use terminal binding leases.
        return !isAcp && acceptsConversationPacket(message)
    }
    val session = (message["sessionName"] ?: message["session-name"]) as? String ?: return false
    val window = ((message["windowIndex"] ?: message["window-index"]) as? Number)?.toInt() ?: return false
    return window == windowIndex && if (isAcp) {
        session.removePrefix("acp_") == sessionName.removePrefix("acp_")
    } else session == sessionName
}
