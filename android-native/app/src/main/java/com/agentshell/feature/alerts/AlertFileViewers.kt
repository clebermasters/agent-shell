package com.agentshell.feature.alerts

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.method.LinkMovementMethod
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// ImageViewer — Full-screen dialog with zoomable image via Coil + pinch-zoom
// ---------------------------------------------------------------------------

@Composable
fun ImageViewer(
    imageBytes: ByteArray,
    filename: String,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = imageBytes,
                    contentDescription = filename,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        },
                )
                // Close button top-end
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
                // Filename label bottom-center
                Text(
                    text = filename,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AudioViewer — ModalBottomSheet with ExoPlayer controls
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioViewer(
    audioBytes: ByteArray,
    filename: String,
    mimeType: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Write bytes to a temp file so ExoPlayer can read from a URI
    val tempUri = remember(audioBytes) {
        val ext = when {
            mimeType.contains("wav") -> ".wav"
            mimeType.contains("ogg") -> ".ogg"
            mimeType.contains("webm") -> ".webm"
            else -> ".mp3"
        }
        val tempFile = java.io.File.createTempFile("alert_audio_", ext, context.cacheDir)
        tempFile.writeBytes(audioBytes)
        android.net.Uri.fromFile(tempFile)
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also { player ->
            player.setMediaItem(MediaItem.fromUri(tempUri))
            player.prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            duration = exoPlayer.duration.coerceAtLeast(0L)
            currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            progress = if (duration > 0) currentPos / duration.toFloat() else 0f
            delay(200)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            exoPlayer.stop()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = filename,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(16.dp))

            // Play / Pause
            IconButton(
                onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // Seek slider
            Slider(
                value = progress,
                onValueChange = { newVal ->
                    if (duration > 0) {
                        exoPlayer.seekTo((newVal * duration).toLong())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Time display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(currentPos / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(duration / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// MarkdownViewer — Dialog with Markwon-rendered content via AndroidView
// ---------------------------------------------------------------------------

@Composable
fun MarkdownViewer(
    markdownBytes: ByteArray,
    filename: String,
    onDismiss: () -> Unit,
) {
    val markdownText = remember(markdownBytes) { String(markdownBytes, Charsets.UTF_8) }
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.let {
        (it.red * 255).roundToInt() < 128
    }

    val textColor = MaterialTheme.colorScheme.onSurface
    val textColorInt = android.graphics.Color.argb(
        (textColor.alpha * 255).roundToInt(),
        (textColor.red * 255).roundToInt(),
        (textColor.green * 255).roundToInt(),
        (textColor.blue * 255).roundToInt(),
    )

    val codeBg = if (isDark) 0xFF282C34.toInt() else 0xFFF1F5F9.toInt()
    val codeText = if (isDark) 0xFF67E8F9.toInt() else 0xFF0369A1.toInt()
    val codeBlockText = if (isDark) 0xFFD4D4D4.toInt() else 0xFF1E293B.toInt()
    val linkCol = if (isDark) 0xFF67E8F9.toInt() else 0xFF0369A1.toInt()
    val density = context.resources.displayMetrics.density
    val scaledDensity = context.resources.displayMetrics.scaledDensity

    val markwon = remember(isDark) {
        Markwon.builder(context)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextColor(textColorInt)
                            textSize = 15f
                            setLineSpacing(4 * density, 1f)
                            setTextIsSelectable(true)
                            movementMethod = LinkMovementMethod.getInstance()
                            linksClickable = true
                        }
                    },
                    update = { tv ->
                        tv.setTextColor(textColorInt)
                        markwon.setMarkdown(tv, markdownText)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// HtmlViewer — Dialog with WebView.loadDataWithBaseURL()
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlViewer(
    htmlBytes: ByteArray,
    filename: String,
    onDismiss: () -> Unit,
) {
    val htmlContent = remember(htmlBytes) { String(htmlBytes, Charsets.UTF_8) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            null,
                            htmlContent,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// PdfViewer — Dialog with PdfRenderer-backed page rendering
// ---------------------------------------------------------------------------

@Composable
fun PdfViewer(
    pdfBytes: ByteArray,
    filename: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pdfFile = remember(pdfBytes, filename) {
        File.createTempFile("agentshell_preview_", ".pdf", context.cacheDir).apply {
            writeBytes(pdfBytes)
        }
    }
    val fileDescriptor = remember(pdfFile.absolutePath) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    val renderer = remember(fileDescriptor) { PdfRenderer(fileDescriptor) }
    val renderMutex = remember(renderer) { Mutex() }

    DisposableEffect(renderer, fileDescriptor, pdfFile) {
        onDispose {
            runCatching { renderer.close() }
            runCatching { fileDescriptor.close() }
            runCatching { pdfFile.delete() }
        }
    }

    val pageCount = remember(renderer) { renderer.pageCount }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.95f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = filename,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "$pageCount page${if (pageCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pageCount) { pageIndex ->
                        PdfPage(
                            renderer = renderer,
                            pageIndex = pageIndex,
                            renderMutex = renderMutex,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FileInfoDialog — Fallback info dialog for unsupported file types
// ---------------------------------------------------------------------------

@Composable
fun FileInfoDialog(
    filename: String,
    size: Long,
    mimeType: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("File Attachment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(label = "Filename", value = filename)
                InfoRow(label = "Type", value = mimeType)
                InfoRow(label = "Size", value = formatFileSize(size))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
fun InlineFileViewer(
    fileBytes: ByteArray,
    filename: String,
    mimeType: String,
    size: Long,
    onDismiss: () -> Unit,
) {
    when {
        isImageFile(mimeType) -> ImageViewer(fileBytes, filename, onDismiss)
        isAudioFile(mimeType) -> AudioViewer(fileBytes, filename, mimeType, onDismiss)
        isMarkdownFile(mimeType, filename) -> MarkdownViewer(fileBytes, filename, onDismiss)
        isHtmlFile(mimeType, filename) -> HtmlViewer(fileBytes, filename, onDismiss)
        isPdfFile(mimeType, filename) -> PdfViewer(fileBytes, filename, onDismiss)
        else -> FileInfoDialog(filename, size, mimeType.ifBlank { "unknown" }, onDismiss)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// Utility helpers
// ---------------------------------------------------------------------------

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

internal fun supportsInlineFilePreview(mimeType: String, filename: String): Boolean {
    return isImageFile(mimeType) ||
        isAudioFile(mimeType) ||
        isMarkdownFile(mimeType, filename) ||
        isHtmlFile(mimeType, filename) ||
        isPdfFile(mimeType, filename)
}

private fun normalizedMimeType(mimeType: String): String =
    mimeType.substringBefore(';').trim().lowercase()

private fun isImageFile(mimeType: String): Boolean =
    normalizedMimeType(mimeType).startsWith("image/")

private fun isAudioFile(mimeType: String): Boolean =
    normalizedMimeType(mimeType).startsWith("audio/")

internal fun isMarkdownFile(mimeType: String, filename: String): Boolean {
    val lowerFilename = filename.lowercase()
    return normalizedMimeType(mimeType) == "text/markdown" ||
        lowerFilename.endsWith(".md") ||
        lowerFilename.endsWith(".markdown")
}

internal fun isHtmlFile(mimeType: String, filename: String): Boolean {
    val lowerFilename = filename.lowercase()
    return normalizedMimeType(mimeType) == "text/html" ||
        lowerFilename.endsWith(".html") ||
        lowerFilename.endsWith(".htm")
}

internal fun isPdfFile(mimeType: String, filename: String): Boolean =
    normalizedMimeType(mimeType) == "application/pdf" || filename.lowercase().endsWith(".pdf")

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    renderMutex: Mutex,
) {
    val context = LocalContext.current
    val targetWidthPx = remember(context.resources.displayMetrics.widthPixels) {
        (context.resources.displayMetrics.widthPixels * 0.82f).roundToInt().coerceAtLeast(720)
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, renderer, pageIndex, targetWidthPx) {
        value = withContext(Dispatchers.IO) {
            renderPdfPage(
                renderer = renderer,
                pageIndex = pageIndex,
                targetWidthPx = targetWidthPx,
                renderMutex = renderMutex,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Page ${pageIndex + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "PDF page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private suspend fun renderPdfPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    targetWidthPx: Int,
    renderMutex: Mutex,
): Bitmap? = runCatching {
    renderMutex.withLock {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = targetWidthPx / page.width.toFloat()
            val targetHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createBitmap(targetWidthPx, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }
}.getOrNull()
