package com.agentshell.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatMessageType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.services.AudioPlayerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MessageBubble(
    message: ChatMessage,
    showThinking: Boolean = true,
    showToolCalls: Boolean = true,
    fileBaseUrl: String = "",
    audioPlayerManager: AudioPlayerManager,
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit = {},
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

    // Hide entire tool bubble when setting is off
    if (isTool && !showToolCalls) return

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
                            fileBaseUrl = fileBaseUrl,
                            audioPlayerManager = audioPlayerManager,
                            serverPathBase = serverPathBase,
                            onOpenServerPath = onOpenServerPath,
                        )
                    }
                } else if (!message.content.isNullOrBlank()) {
                    val contentColor = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                    if (message.isStreaming) {
                        Text(
                            text = message.content,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        MarkdownText(
                            text = message.content,
                            color = contentColor,
                            serverPathBase = serverPathBase,
                            onOpenServerPath = onOpenServerPath,
                        )
                    }
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
    fileBaseUrl: String = "",
    audioPlayerManager: AudioPlayerManager,
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit = {},
) {
    when (block.blockType) {
        ChatBlockType.TEXT -> {
            if (!block.text.isNullOrBlank()) {
                MarkdownText(
                    text = block.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    serverPathBase = serverPathBase,
                    onOpenServerPath = onOpenServerPath,
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
                ToolCallBlock(
                    block = block,
                    serverPathBase = serverPathBase,
                    onOpenServerPath = onOpenServerPath,
                )
            }
        }

        ChatBlockType.TOOL_RESULT -> {
            if (showToolCalls) {
                ToolResultBlock(
                    block = block,
                    serverPathBase = serverPathBase,
                    onOpenServerPath = onOpenServerPath,
                )
            }
        }

        ChatBlockType.IMAGE -> {
            ImageBlock(block = block, fileBaseUrl = fileBaseUrl)
        }

        ChatBlockType.AUDIO -> {
            AudioBlock(block = block, fileBaseUrl = fileBaseUrl, audioPlayerManager = audioPlayerManager)
        }

        ChatBlockType.FILE -> {
            FileBlock(block = block, fileBaseUrl = fileBaseUrl)
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
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.let {
        (it.red * 255).roundToInt() < 128
    }
    val currentOnOpenServerPath by rememberUpdatedState(onOpenServerPath)
    val currentServerPathBase by rememberUpdatedState(serverPathBase)
    val movementMethod = remember {
        ServerPathAwareLinkMovementMethod(
            onLinkClick = { rawUrl ->
                handleMarkdownLinkClick(
                    context = context,
                    rawUrl = rawUrl,
                    serverPathBase = currentServerPathBase,
                    onOpenServerPath = currentOnOpenServerPath,
                )
            },
            findPathUnderOffset = { rawText, offset ->
                findServerPathUnderOffset(rawText, offset, basePath = currentServerPathBase)
            },
        )
    }

    val textColorInt = android.graphics.Color.argb(
        (color.alpha * 255).roundToInt(),
        (color.red * 255).roundToInt(),
        (color.green * 255).roundToInt(),
        (color.blue * 255).roundToInt(),
    )

    val codeBg = if (isDark) 0xFF282C34.toInt() else 0xFFF1F5F9.toInt()
    val codeText = if (isDark) 0xFF67E8F9.toInt() else 0xFF0369A1.toInt()
    val codeBlockText = if (isDark) 0xFFD4D4D4.toInt() else 0xFF1E293B.toInt()
    val linkCol = if (isDark) 0xFF67E8F9.toInt() else 0xFF0369A1.toInt()
    val density = context.resources.displayMetrics.density
    val scaledDensity = context.resources.displayMetrics.scaledDensity

    // Build Markwon once per theme — TablePlugin.create(context) enables scroll-wrapping for wide tables
    val markwon = remember(isDark) {
        Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(codeBg)
                        .codeTextColor(codeText)
                        .codeTextSize((13 * scaledDensity).toInt())
                        .codeBlockBackgroundColor(codeBg)
                        .codeBlockTextColor(codeBlockText)
                        .codeBlockTextSize((13 * scaledDensity).toInt())
                        .codeBlockMargin((8 * density).toInt())
                        .codeTypeface(Typeface.MONOSPACE)
                        .linkColor(linkCol)
                        .headingBreakHeight(0)
                        .blockMargin((8 * density).toInt())
                        .bulletWidth((6 * density).toInt())
                        .blockQuoteWidth((3 * density).toInt())
                }
            })
            .build()
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColorInt)
                textSize = 15f
                setLineSpacing(4 * density, 1f)
                setTextIsSelectable(true)
                setMovementMethod(movementMethod)
                linksClickable = true
            }
        },
        update = { tv ->
            tv.setTextColor(textColorInt)
            tv.setMovementMethod(movementMethod)
            markwon.setMarkdown(tv, text)
        },
    )
}

private class ServerPathAwareLinkMovementMethod(
    private val onLinkClick: (String) -> Unit,
    private val findPathUnderOffset: (CharSequence, Int) -> String?,
) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN) {
            val layout = widget.layout ?: return super.onTouchEvent(widget, buffer, event)
            val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
            val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())

            val spans = buffer.getSpans(offset, (offset + 1).coerceAtMost(buffer.length), URLSpan::class.java)
            if (spans.isNotEmpty()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    onLinkClick(spans[0].url)
                }
                return true
            }

            val lineSpans = buffer.getSpans(
                max(layout.getLineStart(line), 0),
                min(layout.getLineEnd(line) + 1, buffer.length),
                URLSpan::class.java,
            )
            if (lineSpans.isNotEmpty()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    onLinkClick(lineSpans[0].url)
                }
                return true
            }

            if (event.action == MotionEvent.ACTION_UP) {
                val rawText = buffer.toString()
                val detectedPath = findPathUnderOffset(rawText, offset)
                if (detectedPath != null) {
                    onLinkClick(detectedPath)
                    return true
                }
            }
        }

        return super.onTouchEvent(widget, buffer, event)
    }
}

private fun findServerPathUnderOffset(text: CharSequence, offset: Int, basePath: String? = null): String? {
    if (offset !in text.indices) return null
    if (text.isEmpty()) return null

    val regexMatch = extractServerPathFromText(text.toString(), offset, basePath = basePath)
    if (!regexMatch.isNullOrBlank()) {
        return regexMatch
    }

    val isPathBoundary = { c: Char ->
        c.isWhitespace() || c == '\n' || c == '\r' || c == '\t'
    }

    var start = offset
    while (start > 0 && !isPathBoundary(text[start - 1])) {
        start--
    }

    var end = offset
    while (end < text.length && !isPathBoundary(text[end])) {
        end++
    }

    if (start >= end) return null
    val token = text.substring(start, end)
    return parseServerPathFromLink(token, basePath = basePath)
}

private fun handleMarkdownLinkClick(
    context: Context,
    rawUrl: String,
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit,
) {
    val serverPath = parseServerPathFromLink(rawUrl, basePath = serverPathBase)
    if (!serverPath.isNullOrBlank()) {
        onOpenServerPath(serverPath)
        return
    }

    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
    }
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
private fun ToolCallBlock(
    block: ChatBlock,
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit = {},
) {
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
                        MarkdownText(
                            text = inputText,
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            serverPathBase = serverPathBase,
                            onOpenServerPath = onOpenServerPath,
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
private fun ToolResultBlock(
    block: ChatBlock,
    serverPathBase: String? = null,
    onOpenServerPath: (String) -> Unit = {},
) {
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
                        MarkdownText(
                            text = block.content,
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            serverPathBase = serverPathBase,
                            onOpenServerPath = onOpenServerPath,
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
private fun ImageBlock(block: ChatBlock, fileBaseUrl: String = "") {
    val imageUrl = if (fileBaseUrl.isNotBlank() && !block.id.isNullOrBlank()) {
        "$fileBaseUrl/${block.id}"
    } else {
        block.id
    }
    val context = LocalContext.current
    var showFullScreen by remember { mutableStateOf(false) }

    val imageRequest = remember(imageUrl) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                .build()
        }
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = block.altText,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showFullScreen = true },
    )

    if (showFullScreen && imageUrl != null) {
        FullScreenImageDialog(
            imageUrl = imageUrl,
            onDismiss = { showFullScreen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Audio block (ExoPlayer)
// ---------------------------------------------------------------------------

@Composable
private fun AudioBlock(
    block: ChatBlock,
    fileBaseUrl: String = "",
    audioPlayerManager: AudioPlayerManager,
) {
    val audioId = block.id ?: return
    val audioUrl = if (fileBaseUrl.isNotBlank()) "$fileBaseUrl/$audioId" else audioId

    val state by audioPlayerManager.state.collectAsStateWithLifecycle()
    val isThisActive = state.activeAudioId == audioId
    val isPlaying = isThisActive && state.isPlaying
    val isBuffering = isThisActive && state.isBuffering
    val positionMs = if (isThisActive) state.positionMs else 0L
    val durationMs = if (isThisActive) state.durationMs else 0L
    val progress = if (durationMs > 0L) positionMs / durationMs.toFloat() else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledIconButton(
                onClick = {
                    if (isPlaying) audioPlayerManager.pause()
                    else audioPlayerManager.play(audioId, audioUrl)
                },
                modifier = Modifier.size(40.dp),
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = progress,
                    onValueChange = { audioPlayerManager.seekTo(it) },
                    enabled = isThisActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(positionMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatDuration(
                            if (durationMs > 0L) durationMs / 1000
                            else block.durationSeconds?.toLong() ?: 0L,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = isThisActive) {
                IconButton(
                    onClick = { audioPlayerManager.stop() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File block
// ---------------------------------------------------------------------------

@Composable
private fun FileBlock(block: ChatBlock, fileBaseUrl: String = "") {
    val fileUrl = if (fileBaseUrl.isNotBlank() && !block.id.isNullOrBlank()) {
        "$fileBaseUrl/${block.id}"
    } else {
        null
    }
    val context = LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = fileUrl != null && !isDownloading) {
                isDownloading = true
                downloadAndOpenFile(
                    context = context,
                    url = fileUrl!!,
                    filename = block.filename ?: "file",
                    mimeType = block.mimeType ?: "application/octet-stream",
                    onComplete = { isDownloading = false },
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
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
// Fullscreen image dialog
// ---------------------------------------------------------------------------

@Composable
private fun FullScreenImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageRequest = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
            .build()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File download + open helper
// ---------------------------------------------------------------------------

private fun downloadAndOpenFile(
    context: android.content.Context,
    url: String,
    filename: String,
    mimeType: String,
    onComplete: () -> Unit,
) {
    Thread {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Auth-Token", BuildConfig.AUTH_TOKEN)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Download failed: ${response.code}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                return@Thread
            }
            val cacheDir = File(context.cacheDir, "chat_files")
            cacheDir.mkdirs()
            val file = File(cacheDir, filename)
            file.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "No app to open this file", Toast.LENGTH_SHORT).show()
                }
                onComplete()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }.start()
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
