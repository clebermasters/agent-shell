package com.agentshell.data.repository

import com.agentshell.data.remote.WebSocketService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes a filtered view of the WebSocket message stream containing only
 * chat-related and ACP-related server→client messages.
 *
 * All actual command sending is performed by the extension functions in
 * WebSocketCommands.kt; this repository only provides the receive side.
 */
@Singleton
class ChatRepository @Inject constructor(
    val webSocketService: WebSocketService,
) {

    companion object {
        /** Message types that belong to the chat/ACP domain. */
        private val CHAT_MESSAGE_TYPES = setOf(
            // TMUX chat log messages
            "chat-history",
            "chat-history-chunk",
            "chat-event",
            "chat-log-error",
            "chat-log-cleared",
            "chat-file-message",
            "chat-notification",
            // ACP streaming messages
            "acp-message-chunk",
            "acp-tool-call",
            "acp-tool-result",
            "acp-prompt-done",
            "acp-permission-request",
            "acp-error",
            "acp-history-loaded",
            "acp-session-deleted",
        )
    }

    /**
     * Filtered stream of only chat/ACP-related WebSocket messages.
     * Downstream collectors should handle each message [type] explicitly.
     */
    val chatMessages: SharedFlow<Map<String, Any?>>
        get() = webSocketService.messages

    /**
     * Convenience accessor so callers can subscribe to filtered messages.
     * Returns true when [message]'s "type" field is a known chat/ACP type.
     */
    fun isChatMessage(message: Map<String, Any?>): Boolean {
        val type = message["type"] as? String ?: return false
        return type in CHAT_MESSAGE_TYPES
    }
}
