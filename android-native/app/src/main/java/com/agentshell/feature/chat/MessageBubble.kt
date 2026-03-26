package com.agentshell.feature.chat

import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MessageBubble(
    message: ChatMessage,
    showThinking: Boolean = true,
    showToolCalls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isUser = message.messageType == ChatMessageType.USER
    val isTool = message.messageType == ChatMessageType.TOOL_CALL ||
            message.messageType == ChatMessageType.TOOL_RESULT ||
            message.messageType == ChatMessageType.TOOL
    val isSystem = message.messageType == ChatMessageType.SYSTEM
    val isError = message.messageType == ChatMessageType.ERROR

    if (isSystem) {
        // Render system messages as centred captions
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.content ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontStyle = FontStyle.Italic,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Header row: avatar + label
        MessageHeader(isUser = isUser, isError = isError, isTool = isTool)

        Spacer(Modifier.height(4.dp))

        // Bubble
        Surface(
            modifier = Modifier.widthIn(max = if (isUser) 320.dp else Int.MAX_VALUE.dp),
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isError -> MaterialTheme.colorScheme.errorContainer
                isTool -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.blocks.isNotEmpty()) {
                    message.blocks.forEach { block ->
                        BlockContent(
                            block = block,
                            showThinking = showThinking,
                            showToolCalls = showToolCalls,
                        )
                    }
                } else if (!message.content.isNullOrBlank()) {
                    MarkdownText(
                        text = message.content,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Streaming dots appended to streaming messages
                if (message.isStreaming) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        repeat(3) { idx ->
                            val pulsed = remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                delay(idx * 200L)
                                while (true) {
                                    pulsed.value = true
                                    delay(600)
                                    pulsed.value = false
                                    delay(600)
                                }
                            }
                            val alpha by animateFloatAsState(
                                targetValue = if (pulsed.value) 1f else 0.3f,
                                animationSpec = tween(300),
                                label = "pulse$idx",
                            )
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .alpha(alpha)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                        CircleShape,
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Timestamp
        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun MessageHeader(isUser: Boolean, isError: Boolean, isTool: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when {
            isUser -> Icons.Default.SmartToy // placeholder; user icon would be person
            isError -> Icons.Default.Close
            isTool -> Icons.Default.Build
            else -> Icons.Default.SmartToy
        }
        val label = when {
            isUser -> "You"
            isError -> "Error"
            isTool -> "Tool"
            else -> "Assistant"
        }
        val tint = when {
            isUser -> MaterialTheme.colorScheme.primary
            isError -> MaterialTheme.colorScheme.error
            isTool -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

// ---------------------------------------------------------------------------
// Block renderers
// ---------------------------------------------------------------------------

@Composable
private fun BlockContent(
    block: ChatBlock,
    showThinking: Boolean,
    showToolCalls: Boolean,
) {
    when (block.blockType) {
        ChatBlockType.TEXT -> {
            if (!block.text.isNullOrBlank()) {
                MarkdownText(
                    text = block.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ChatBlockType.THINKING -> {
            if (showThinking) {
                ThinkingBlock(content = block.content ?: "")
            }
        }

        ChatBlockType.TOOL_CALL -> {
            if (showToolCalls) {
                ToolCallBlock(block = block)
            }
        }

        ChatBlockType.TOOL_RESULT -> {
            if (showToolCalls) {
                ToolResultBlock(block = block)
            }
        }

        ChatBlockType.IMAGE -> {
            ImageBlock(block = block)
        }

        ChatBlockType.AUDIO -> {
            AudioBlock(block = block)
        }

        ChatBlockType.FILE -> {
            FileBlock(block = block)
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown text (via Markwon, wrapped in AndroidView)
// ---------------------------------------------------------------------------

@Composable
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .build()
    }
    val textColor = color.copy(alpha = 1f)
    val colorInt = android.graphics.Color.argb(
        (textColor.alpha * 255).roundToInt(),
        (textColor.red * 255).roundToInt(),
        (textColor.green * 255).roundToInt(),
        (textColor.blue * 255).roundToInt(),
    )
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(colorInt)
                textSize = 14f
            }
        },
        update = { textView ->
            textView.setTextColor(colorInt)
            markwon.setMarkdown(textView, text)
        },
    )
}

// ---------------------------------------------------------------------------
// Thinking block
// ---------------------------------------------------------------------------

@Composable
private fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontStyle = FontStyle.Italic,
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tool call block
// ---------------------------------------------------------------------------

@Composable
private fun ToolCallBlock(block: ChatBlock) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Status indicator dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Text(
                        text = block.toolName ?: "Tool",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!block.summary.isNullOrBlank()) {
                        Text(
                            text = block.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }

            // Collapsible input JSON
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                val inputText = block.input?.entries?.joinToString("\n") { (k, v) -> "  $k: $v" } ?: ""
                if (inputText.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = inputText,
                            modifier = Modifier.padding(8.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool result block
// ---------------------------------------------------------------------------

@Composable
private fun ToolResultBlock(block: ChatBlock) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val isSuccess = block.summary?.lowercase() != "error"
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336),
                                CircleShape,
                            )
                    )
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!block.summary.isNullOrBlank()) {
                        Text(
                            text = block.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                if (!block.content.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = block.content,
                            modifier = Modifier.padding(8.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Image block
// ---------------------------------------------------------------------------

@Composable
private fun ImageBlock(block: ChatBlock) {
    AsyncImage(
        model = block.id,  // URL or media ID resolved externally
        contentDescription = block.altText,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
    )
}

// ---------------------------------------------------------------------------
// Audio block (ExoPlayer)
// ---------------------------------------------------------------------------

@Composable
private fun AudioBlock(block: ChatBlock) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also { player ->
            if (!block.id.isNullOrBlank()) {
                player.setMediaItem(MediaItem.fromUri(block.id))
                player.prepare()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            duration = exoPlayer.duration.coerceAtLeast(0L)
            val pos = exoPlayer.currentPosition
            progress = if (duration > 0) pos / duration.toFloat() else 0f
            delay(200)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val durationText = formatDuration(block.durationSeconds?.toLong() ?: (duration / 1000))
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File block
// ---------------------------------------------------------------------------

@Composable
private fun FileBlock(block: ChatBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.filename ?: "File",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                val sizeText = block.sizeBytes?.let { formatFileSize(it) } ?: ""
                val mimeText = block.mimeType ?: ""
                val subtitle = listOf(mimeText, sizeText).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utility helpers
// ---------------------------------------------------------------------------

private val timestampFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTimestamp(epochMillis: Long): String =
    timestampFmt.format(Date(epochMillis))

private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val m = s / 60
    return "%d:%02d".format(m, s % 60)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
