package com.agentshell.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryFilePage(val text: String, val nextOffset: Long, val hasMore: Boolean)

/** UTF-8 boundaries are retained so every byte remains reachable across pages. */
internal fun readHistoryFilePage(file: File, offset: Long, pageBytes: Int = 64 * 1024): HistoryFilePage {
    require(offset >= 0 && pageBytes >= 4)
    RandomAccessFile(file, "r").use { input ->
        input.seek(offset)
        val bytes = ByteArray(pageBytes)
        val read = input.read(bytes)
        if (read <= 0) return HistoryFilePage("", offset, false)
        var end = read
        repeat(4) {
            val text = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end)).toString()
            }.getOrNull()
            if (text != null) {
                val next = offset + end
                return HistoryFilePage(text, next, next < input.length())
            }
            end--
        }
        error("The message file contains invalid UTF-8")
    }
}

@Composable
internal fun LargeHistoryViewer(file: File, filename: String, onDismiss: () -> Unit) {
    var offsets by remember(file) { mutableStateOf(listOf(0L)) }
    var pageNumber by remember(file) { mutableIntStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val page by produceState<HistoryFilePage?>(null, file, pageNumber) {
        value = null
        error = null
        value = try { withContext(Dispatchers.IO) { readHistoryFilePage(file, offsets[pageNumber]) } }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = failure.message
            null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(filename, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("Page ${pageNumber + 1}", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (page != null) key(pageNumber) {
                        SelectionContainer {
                            Text(page!!.text, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        }
                    } else if (error != null) Text(error!!) else CircularProgressIndicator()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = pageNumber > 0 && page != null, onClick = { pageNumber-- }) { Text("Previous") }
                    TextButton(enabled = page?.hasMore == true, onClick = {
                        val next = page!!.nextOffset
                        if (offsets.size <= pageNumber + 1) offsets = offsets + next
                        pageNumber++
                    }) { Text("Next") }
                }
            }
        }
    }
}
