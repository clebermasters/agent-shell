package com.agentshell.feature.splitscreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import com.agentshell.data.model.ChatMessageParser
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.AgentActivity
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.feature.chat.AgentActivityBar
import com.agentshell.data.model.ChatCursor
import com.agentshell.data.remote.SessionSocket
import com.agentshell.feature.chat.MarkdownText
import com.agentshell.feature.chat.CopyMessageButton
import com.agentshell.feature.chat.UnreadDivider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Lightweight chat panel for the split-screen feature.
 * Displays messages with text content and a compact input bar.
 * Uses a dedicated WebSocket so multiple panels can stay live concurrently.
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
    val activityRepository = services.chatActivityRepository()
    val selectedHost by remember(services) { services.hostRepository().getSelectedHost() }.collectAsStateWithLifecycle(initialValue = null)
    val target = remember(selectedHost?.id, sessionName, windowIndex, isAcp) {
        selectedHost?.let { ChatTarget.create(it.id, sessionName, windowIndex, isAcp) }
    }
    val readStates by activityRepository.states.collectAsStateWithLifecycle()
    val agentActivities by activityRepository.agentActivities.collectAsStateWithLifecycle()
    val readOwner = "split-chat:$panelId"
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenActive by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var readReady by remember(target?.key) { mutableStateOf(false) }
    var historyReady by remember(target?.key) { mutableStateOf(false) }
    var initialScrollComplete by remember(target?.key) { mutableStateOf(false) }
    var firstUnreadId by remember(target?.key) { mutableStateOf<String?>(null) }
    var visitUnreadCursor by remember(target?.key) { mutableStateOf<ChatCursor?>(null) }
    var followLive by remember(target?.key) { mutableStateOf(true) }
    val mainConnection by services.webSocketService().connectionStatus.collectAsStateWithLifecycle()
    val webSocketUrl = services.webSocketService().currentWebSocketUrl?.takeIf {
        mainConnection == ConnectionStatus.CONNECTED && it.substringBefore('?') == selectedHost?.let { host -> "${host.wsUrl}/ws" }
    }
    val panelSocket = remember(panelId, target?.hostId) { SessionSocket(services.okHttpClient()) }
    val isSocketConnected by panelSocket.isConnected.collectAsStateWithLifecycle()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(target?.key) {
        val chat = target ?: return@LaunchedEffect
        visitUnreadCursor = activityRepository.register(chat).unread.firstOrNull()
        readReady = true
    }

    DisposableEffect(lifecycleOwner, target?.key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) screenActive = true
            if (event == Lifecycle.Event.ON_PAUSE) {
                screenActive = false
                initialScrollComplete = false
                firstUnreadId = null
                visitUnreadCursor = null
                activityRepository.setViewing(readOwner, null, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activityRepository.setViewing(readOwner, null, false)
        }
    }

    LaunchedEffect(webSocketUrl, panelSocket) {
        if (!webSocketUrl.isNullOrEmpty()) {
            panelSocket.connect(webSocketUrl)
        }
    }

    DisposableEffect(panelSocket) {
        onDispose {
            panelSocket.dispose()
        }
    }

    LaunchedEffect(isSocketConnected, sessionName, windowIndex, isAcp) {
        if (!isSocketConnected || sessionName.isEmpty()) return@LaunchedEffect
        messages.clear()
        if (isAcp) {
            panelSocket.send(
                mapOf(
                    "type" to "watch-acp-chat-log",
                    "sessionId" to sessionName,
                    "windowIndex" to windowIndex,
                ),
            )
        } else {
            panelSocket.send(
                mapOf(
                    "type" to "watch-chat-log",
                    "sessionName" to sessionName,
                    "windowIndex" to windowIndex,
                ),
            )
        }
    }

    // Collect messages — runs once on composition, filters by session
    LaunchedEffect(panelId, target?.key, panelSocket, sessionName, windowIndex, isAcp) {
        messages.clear()
        if (sessionName.isEmpty()) return@LaunchedEffect

        panelSocket.messages.collect { message ->
            target?.let { activityRepository.handleAgentActivity(it.hostId, message, it) }
            val type = message["type"] as? String ?: return@collect
            when (type) {
                "chat-history" -> {
                    if (!matchesTmuxSession(message, sessionName, windowIndex, isAcp)) return@collect
                    val parsed = parseMessageList(message["messages"])
                    withContext(Dispatchers.Main) {
                        messages.clear()
                        messages.addAll(parsed)
                    }
                    target?.let { activityRepository.history(it, parsed) }
                    historyReady = true
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
                        val existing = messages.indexOfFirst { it.id == parsed.id }
                        if (existing >= 0) messages[existing] = parsed else messages.add(parsed)
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
                    target?.let { activityRepository.history(it, parsed) }
                    historyReady = true
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

    LaunchedEffect(isDragging) { if (isDragging) followLive = false }

    LaunchedEffect(messages.size, readStates[target?.key], historyReady, readReady, screenActive) {
        val chat = target ?: return@LaunchedEffect
        if (!readReady || !historyReady || messages.isEmpty() || !screenActive) return@LaunchedEffect
        val state = readStates[chat.key]
        if ((firstUnreadId == null || messages.none { it.id == firstUnreadId }) && state != null) {
            visitUnreadCursor = visitUnreadCursor ?: state.unread.firstOrNull()
            visitUnreadCursor?.let { cursor ->
                val index = state.copy(unread = listOf(cursor)).unreadIndex(messages, hasMore = false)
                firstUnreadId = index?.let { messages[it].id }
            }
        }
        if (!initialScrollComplete) {
            val unreadIndex = messages.indexOfFirst { it.id == firstUnreadId }
            listState.scrollToItem(if (unreadIndex >= 0) unreadIndex else messages.lastIndex)
            followLive = unreadIndex < 0
            initialScrollComplete = true
        } else if (followLive && !listState.isScrollInProgress) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(target?.key, initialScrollComplete, screenActive) {
        val chat = target ?: return@LaunchedEffect
        if (!initialScrollComplete || !screenActive) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val visibleKeys = layout.visibleItemsInfo.map { it.key }.toSet()
            val lastVisible = messages.lastOrNull { it.id in visibleKeys }?.id
            val lastItem = layout.visibleItemsInfo.lastOrNull()
            val atBottom = lastItem != null && lastItem.index == layout.totalItemsCount - 1 && lastItem.offset + lastItem.size <= layout.viewportEndOffset
            Triple(lastVisible, atBottom, lastVisible != null && lastVisible == messages.lastOrNull()?.id)
        }.distinctUntilChanged().collect { (messageId, atBottom, viewingLatest) ->
            activityRepository.setViewing(readOwner, chat, viewingLatest)
            if (atBottom) followLive = true
            val index = messages.indexOfFirst { it.id == messageId }
            if (index >= 0) activityRepository.readThrough(chat, messages.take(index + 1))
        }
    }

    // Focus text input when panel gains focus
    LaunchedEffect(isFocused) {
        if (isFocused) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AgentActivityBar(agentActivities[target?.key] ?: AgentActivity(), isSocketConnected)
        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages.toList(), key = { it.id }) { msg ->
                Column {
                    if (msg.id == firstUnreadId) UnreadDivider()
                    CompactMessageBubble(msg)
                }
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
                        panelSocket.send(
                            mapOf(
                                "type" to "acp-send-prompt",
                                "sessionId" to sessionName,
                                "message" to text,
                            ),
                        )
                    } else {
                        panelSocket.send(
                            mapOf(
                                "type" to "send-chat-message",
                                "sessionName" to sessionName,
                                "windowIndex" to windowIndex,
                                "message" to text,
                                "notify" to false,
                            ),
                        )
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                CopyMessageButton(message)
            }

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

private fun parseMessage(data: Map<String, Any?>): ChatMessage = ChatMessageParser.parse(data)
