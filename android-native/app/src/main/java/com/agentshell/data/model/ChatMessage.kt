package com.agentshell.data.model

import kotlinx.serialization.Serializable

enum class ChatMessageType {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR;

    companion object {
        fun fromString(value: String?): ChatMessageType = when (value?.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "system" -> SYSTEM
            "tool" -> TOOL
            "toolcall", "tool_call" -> TOOL_CALL
            "toolresult", "tool_result" -> TOOL_RESULT
            "error" -> ERROR
            else -> SYSTEM
        }
    }
}

enum class ChatBlockType {
    TEXT,
    TOOL_CALL,
    TOOL_RESULT,
    THINKING,
    IMAGE,
    AUDIO,
    FILE;

    companion object {
        fun fromString(value: String?): ChatBlockType = when (value?.lowercase()) {
            "text" -> TEXT
            "toolcall", "tool_call" -> TOOL_CALL
            "toolresult", "tool_result" -> TOOL_RESULT
            "thinking" -> THINKING
            "image" -> IMAGE
            "audio" -> AUDIO
            "file" -> FILE
            else -> TEXT
        }
    }
}

@Serializable
data class ChatBlock(
    val type: String,
    val text: String? = null,
    val toolName: String? = null,
    val summary: String? = null,
    val input: Map<String, String>? = null,
    val content: String? = null,
    val id: String? = null,
    val mimeType: String? = null,
    val altText: String? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val durationSeconds: Double? = null
) {
    val blockType: ChatBlockType
        get() = ChatBlockType.fromString(type)
}

@Serializable
data class ChatMessage(
    val id: String,
    val type: String,
    val content: String? = null,
    val timestamp: Long,
    val toolName: String? = null,
    val isStreaming: Boolean = false,
    val blocks: List<ChatBlock> = emptyList(),
    val summary: String? = null
) {
    val messageType: ChatMessageType
        get() = ChatMessageType.fromString(type)
}
