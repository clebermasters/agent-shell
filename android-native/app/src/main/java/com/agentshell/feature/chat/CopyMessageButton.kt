package com.agentshell.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import kotlinx.coroutines.delay

/** Copy the reply itself, preserving code and Markdown without hidden thinking or tool output. */
@Composable
fun CopyMessageButton(message: ChatMessage) {
    if (message.messageType != ChatMessageType.ASSISTANT) return
    val text = if (message.blocks.isEmpty()) message.content.orEmpty() else message.blocks
        .filter { it.blockType == ChatBlockType.TEXT }.mapNotNull { it.text }.joinToString("\n\n")
    if (text.isBlank()) return
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1_500); copied = false }
    }
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (copied) "Message copied" else "Copy message",
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
