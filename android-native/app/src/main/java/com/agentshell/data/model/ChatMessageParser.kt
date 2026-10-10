package com.agentshell.data.model

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Stable IDs let read positions survive history reloads and app restarts. */
object ChatMessageParser {
    @Suppress("UNCHECKED_CAST")
    fun parse(data: Map<String, Any?>): ChatMessage {
        val role = (data["role"] ?: data["type"]) as? String ?: "assistant"
        val blocks = (data["blocks"] as? List<*>)?.mapNotNull { raw ->
            val block = raw as? Map<String, Any?> ?: return@mapNotNull null
            val input = (block["input"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }?.toSortedMap()
                ?: (block["input"] as? String)?.let { value ->
                    runCatching { JSONObject(value).let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } } }.getOrNull()
                }
            ChatBlock(
                type = block["type"] as? String ?: "text",
                title = block["title"] as? String,
                html = block["html"] as? String,
                text = block["text"] as? String,
                content = block["content"] as? String,
                toolName = (block["name"] ?: block["toolName"]) as? String,
                summary = block["summary"] as? String,
                input = input,
                id = block["id"] as? String,
                mimeType = block["mimeType"] as? String,
                altText = block["altText"] as? String,
                filename = block["filename"] as? String,
                sizeBytes = (block["sizeBytes"] as? Number)?.toLong(),
                durationSeconds = (block["durationSeconds"] as? Number)?.toDouble(),
            )
        } ?: emptyList()
        val content = ((data["content"] ?: data["text"]) as? String)?.takeIf { it.isNotBlank() }
            ?: blocks.filter { it.blockType == ChatBlockType.TEXT }.joinToString("\n") { it.text.orEmpty() }.ifBlank { null }
        val timestamp = when (val raw = data["timestamp"]) {
            is Number -> raw.toLong()
            is String -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?: raw.toLongOrNull() ?: 0L
            else -> 0L
        }
        val type = when {
            role == "user" -> "user"
            blocks.any { it.blockType == ChatBlockType.TOOL_CALL } -> "tool_call"
            blocks.any { it.blockType == ChatBlockType.TOOL_RESULT } -> "tool_result"
            else -> role
        }
        val id = (data["id"] ?: data["messageId"] ?: data["uuid"])?.toString()?.takeIf { it.isNotBlank() }
            ?: chatFingerprint("$role\u0000$timestamp\u0000${content.orEmpty()}\u0000${Json.encodeToString(blocks)}")
        return ChatMessage(id = id, type = type, content = content, timestamp = timestamp, blocks = blocks)
    }
}
