package com.agentshell.feature.splitscreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import com.agentshell.feature.chat.MarkdownText
import com.agentshell.data.remote.acpSendPrompt
import com.agentshell.data.remote.sendChatMessage
import com.agentshell.data.remote.watchAcpChatLog
import com.agentshell.data.remote.watchChatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Lightweight chat panel for the split-screen feature.
 * Displays messages with text content and a compact input bar.
 * Uses the same message parsing logic as [ChatViewModel].
 */
@Composable
fun ChatPanelContent(
    panelId: String,
    sessionName: String,
    windowIndex: Int,
    isAcp: Boolean,
    isFocused: Boolean,
) {
    val services = rememberSplitScreenServices()
    val webSocketService = services.webSocketService()
    val chatRepository = services.chatRepository()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Only the focused chat panel sends the watch command to the backend.
    // The backend's watch-chat-log handler sets current_session, cancelling
    // any previous watcher — so only one chat session can be "live" at a time.
    // Non-focused panels keep their loaded messages but don't receive live updates.
    LaunchedEffect(isFocused, sessionName, windowIndex, isAcp) {
        if (sessionName.isEmpty()) return@LaunchedEffect
        if (isFocused) {
            if (isAcp) {
                webSocketService.watchAcpChatLog(sessionName)
            } else {
                webSocketService.watchChatLog(sessionName, windowIndex)
            }
        }
    }

    // Collect messages — runs once on composition, filters by session
    LaunchedEffect(panelId, sessionName, windowIndex, isAcp) {
        messages.clear()
        if (sessionName.isEmpty()) return@LaunchedEffect

        chatRepository.chatMessages.collect { message ->
            val type = message["type"] as? String ?: return@collect
            when (type) {
                "chat-history" -> {
                    if (!matchesTmuxSession(message, sessionName, windowIndex, isAcp)) return@collect
                    val parsed = parseMessageList(message["messages"])
                    withContext(Dispatchers.Main) {
                        messages.clear()
                        messages.addAll(parsed)
                    }
                }
                "chat-history-chunk" -> {
                    if (!matchesTmuxSession(message, sessionName, windowIndex, isAcp)) return@collect
                    val parsed = parseMessageList(message["messages"])
                    withContext(Dispatchers.Main) {
                        messages.addAll(0, parsed) // Prepend older messages
                    }
                }
                "chat-event" -> {
                    if (isAcp) return@collect
                    if (!matchesTmuxSession(message, sessionName, windowIndex, isAcp)) return@collect
                    @Suppress("UNCHECKED_CAST")
                    val msgData = message["message"] as? Map<String, Any?> ?: return@collect
                    val source = message["source"] as? String
                    val parsed = parseMessage(msgData) ?: return@collect
                    // Skip echoed user messages from backend (already added locally)
                    if (parsed.messageType == ChatMessageType.USER && source != "webhook") return@collect
                    withContext(Dispatchers.Main) {
                        // Merge consecutive assistant messages
                        if (messages.isNotEmpty() &&
                            messages.last().messageType == ChatMessageType.ASSISTANT &&
                            parsed.messageType == ChatMessageType.ASSISTANT
                        ) {
                            val last = messages.last()
                            val mergedContent = listOfNotNull(last.content, parsed.content).joinToString("")
                            val mergedBlocks = last.blocks + parsed.blocks
                            messages[messages.size - 1] = last.copy(content = mergedContent, blocks = mergedBlocks)
                        } else {
                            messages.add(parsed)
                        }
                    }
                }
                "acp-message-chunk" -> {
                    if (!isAcp) return@collect
                    val sid = message["sessionId"] as? String ?: return@collect
                    if (sid != sessionName) return@collect
                    val text = message["content"] as? String ?: message["text"] as? String ?: return@collect
                    val isThinking = message["isThinking"] as? Boolean ?: false
                    withContext(Dispatchers.Main) {
                        if (isThinking) {
                            // Merge thinking chunks
                            if (messages.isNotEmpty() &&
                                messages.last().messageType == ChatMessageType.ASSISTANT &&
                                messages.last().blocks.size == 1 &&
                                messages.last().blocks.first().blockType == ChatBlockType.THINKING
                            ) {
                                val last = messages.last()
                                val oldContent = last.blocks.first().content ?: ""
                                messages[messages.size - 1] = last.copy(
                                    blocks = listOf(ChatBlock(type = "thinking", content = oldContent + text)),
                                )
                            } else {
                                messages.add(ChatMessage(
                                    id = "think-${System.currentTimeMillis()}",
                                    type = "assistant",
                                    content = null,
                                    timestamp = System.currentTimeMillis(),
                                    blocks = listOf(ChatBlock(type = "thinking", content = text)),
                                ))
                            }
                        } else {
                            // Merge text chunks
                            if (messages.isNotEmpty() &&
                                messages.last().messageType == ChatMessageType.ASSISTANT &&
                                messages.last().blocks.isEmpty()
                            ) {
                                val last = messages.last()
                                messages[messages.size - 1] = last.copy(content = (last.content ?: "") + text)
                            } else {
                                messages.add(ChatMessage(
                                    id = "acp-${System.currentTimeMillis()}",
                                    type = "assistant",
                                    content = text,
                                    timestamp = System.currentTimeMillis(),
                                ))
                            }
                        }
                    }
                }
                "acp-history-loaded" -> {
                    if (!isAcp) return@collect
                    val sid = message["sessionId"] as? String ?: return@collect
                    // Backend sends sessionId with "acp_" prefix sometimes
                    if (sid != sessionName && sid != "acp_$sessionName") return@collect
                    val parsed = parseMessageList(message["messages"])
                    withContext(Dispatchers.Main) {
                        messages.clear()
                        messages.addAll(parsed)
                    }
                }
                "acp-tool-call" -> {
                    if (!isAcp) return@collect
                    val sid = message["sessionId"] as? String ?: return@collect
                    if (sid != sessionName && sid != "acp_$sessionName") return@collect
                    val toolCallId = message["toolCallId"] as? String ?: UUID.randomUUID().toString()
                    val title = message["title"] as? String ?: "Unknown Tool"
                    val kind = message["kind"] as? String ?: ""
                    withContext(Dispatchers.Main) {
                        messages.add(
                            ChatMessage(
                                id = "tool_call:$toolCallId",
                                type = "tool_call",
                                timestamp = System.currentTimeMillis(),
                                blocks = listOf(
                                    ChatBlock(type = "tool_call", toolName = title, summary = kind),
                                ),
                            )
                        )
                    }
                }
                "acp-tool-result" -> {
                    if (!isAcp) return@collect
                    val sid = message["sessionId"] as? String ?: return@collect
                    if (sid != sessionName && sid != "acp_$sessionName") return@collect
                    val toolCallId = message["toolCallId"] as? String ?: UUID.randomUUID().toString()
                    val status = message["status"] as? String ?: ""
                    val output = message["output"] as? String ?: ""
                    withContext(Dispatchers.Main) {
                        messages.add(
                            ChatMessage(
                                id = "tool_result:$toolCallId",
                                type = "tool_result",
                                timestamp = System.currentTimeMillis(),
                                blocks = listOf(
                                    ChatBlock(type = "tool_result", content = output, summary = status),
                                ),
                            )
                        )
                    }
                }
            }
        }
    }

    // Auto-scroll on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Focus text input when panel gains focus
    LaunchedEffect(isFocused) {
        if (isFocused) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages.toList(), key = { it.id }) { msg ->
                CompactMessageBubble(msg)
            }
        }

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Message…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    val text = inputText.trim()
                    if (text.isEmpty()) return@IconButton
                    if (isAcp) {
                        webSocketService.acpSendPrompt(sessionName, text)
                    } else {
                        webSocketService.sendChatMessage(sessionName, windowIndex, text)
                    }
                    messages.add(ChatMessage(
                        id = "user-${System.currentTimeMillis()}",
                        type = "user",
                        content = text,
                        timestamp = System.currentTimeMillis(),
                    ))
                    inputText = ""
                    coroutineScope.launch {
                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Message Rendering ───────────────────────────────────────────────────────

@Composable
private fun CompactMessageBubble(message: ChatMessage) {
    val isUser = message.messageType == ChatMessageType.USER
    val isToolCall = message.messageType == ChatMessageType.TOOL_CALL
    val isToolResult = message.messageType == ChatMessageType.TOOL_RESULT

    val bg = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isToolCall || isToolResult -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    val label = when {
        isUser -> "You"
        isToolCall -> "Tool Call"
        isToolResult -> "Tool Result"
        else -> "Assistant"
    }

    // Extract display text from blocks or content
    val displayText = extractDisplayText(message)
    if (displayText.isBlank() && message.blocks.isEmpty()) return // Skip truly empty messages

    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)

            // Render blocks if present
            if (message.blocks.isNotEmpty()) {
                message.blocks.forEach { block ->
                    when (block.blockType) {
                        ChatBlockType.TEXT -> {
                            val text = block.text ?: ""
                            if (text.isNotEmpty()) {
                                MarkdownText(
                                    text = text,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        ChatBlockType.THINKING -> {
                            Text(
                                text = "💭 ${(block.content ?: "").take(200)}${if ((block.content?.length ?: 0) > 200) "…" else ""}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ChatBlockType.TOOL_CALL -> {
                            Text(
                                text = "🔧 ${block.toolName ?: "tool"}: ${block.summary ?: ""}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ChatBlockType.TOOL_RESULT -> {
                            Text(
                                text = "✓ ${block.toolName ?: "result"}: ${block.summary ?: block.content?.take(100) ?: ""}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> {
                            Text(
                                text = "[${block.blockType.name.lowercase()}]",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            } else if (displayText.isNotEmpty()) {
                // Fallback to content field — render as markdown
                MarkdownText(
                    text = displayText,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun extractDisplayText(message: ChatMessage): String {
    // First try content field
    if (!message.content.isNullOrBlank()) return message.content

    // Then try text blocks
    val textFromBlocks = message.blocks
        .filter { it.blockType == ChatBlockType.TEXT }
        .mapNotNull { it.text }
        .joinToString("\n")
    if (textFromBlocks.isNotBlank()) return textFromBlocks

    return ""
}

// ── Message Parsing (matches ChatViewModel.parseMessage) ────────────────────

private fun matchesTmuxSession(
    message: Map<String, Any?>,
    sessionName: String,
    windowIndex: Int,
    isAcp: Boolean,
): Boolean {
    if (isAcp) return false // ACP messages use different handlers
    val msgSession = (message["sessionName"] ?: message["session-name"]) as? String ?: return false
    val msgWindow = (message["windowIndex"] ?: message["window-index"])?.let { (it as? Number)?.toInt() } ?: return false
    return msgSession == sessionName && msgWindow == windowIndex
}

@Suppress("UNCHECKED_CAST")
private fun parseMessageList(raw: Any?): List<ChatMessage> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item as? Map<String, Any?> ?: return@mapNotNull null
        parseMessage(map)
    }
}

/**
 * Parses a raw message map to [ChatMessage], matching the logic in
 * ChatViewModel.parseMessage exactly.
 */
@Suppress("UNCHECKED_CAST")
private fun parseMessage(data: Map<String, Any?>): ChatMessage? {
    val role = data["role"] as? String ?: data["type"] as? String ?: "assistant"
    val blocksData = data["blocks"] as? List<*> ?: emptyList<Any>()

    val blocks = blocksData.mapNotNull { b ->
        val block = b as? Map<String, Any?> ?: return@mapNotNull null
        val blockType = block["type"] as? String ?: "text"
        when (blockType) {
            "tool_call" -> ChatBlock(
                type = "tool_call",
                toolName = block["name"] as? String,
                summary = block["summary"] as? String,
            )
            "tool_result" -> ChatBlock(
                type = "tool_result",
                toolName = block["toolName"] as? String,
                content = block["content"] as? String,
                summary = block["summary"] as? String,
            )
            "thinking" -> ChatBlock(
                type = "thinking",
                content = block["content"] as? String ?: "",
            )
            "image" -> ChatBlock(
                type = "image",
                id = block["id"] as? String ?: "",
                mimeType = block["mimeType"] as? String ?: "",
                altText = block["altText"] as? String,
            )
            "audio" -> ChatBlock(
                type = "audio",
                id = block["id"] as? String ?: "",
                mimeType = block["mimeType"] as? String ?: "",
                durationSeconds = (block["durationSeconds"] as? Number)?.toDouble(),
            )
            "file" -> ChatBlock(
                type = "file",
                id = block["id"] as? String ?: "",
                filename = block["filename"] as? String ?: "",
                mimeType = block["mimeType"] as? String ?: "",
                sizeBytes = (block["sizeBytes"] as? Number)?.toLong(),
            )
            else -> ChatBlock(type = "text", text = block["text"] as? String ?: "")
        }
    }

    // Derive content from text blocks
    val textContent = blocks
        .filter { it.blockType == ChatBlockType.TEXT }
        .joinToString("\n") { it.text ?: "" }

    // Derive message type
    val type = when {
        role == "user" -> "user"
        blocks.any { it.blockType == ChatBlockType.TOOL_CALL } -> "tool_call"
        blocks.any { it.blockType == ChatBlockType.TOOL_RESULT } -> "tool_result"
        else -> "assistant"
    }

    // Also handle raw content field (for simple messages without blocks)
    val rawContent = data["content"] as? String ?: data["text"] as? String

    val timestampRaw = data["timestamp"] as? String
    val timestamp = timestampRaw?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

    val finalContent = textContent.ifEmpty { rawContent }

    return ChatMessage(
        id = (data["id"] as? String) ?: UUID.randomUUID().toString(),
        type = type,
        content = finalContent?.ifEmpty { null },
        timestamp = timestamp,
        blocks = blocks,
    )
}
