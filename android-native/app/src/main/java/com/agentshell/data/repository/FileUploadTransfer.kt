package com.agentshell.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

// Base64 plus the JSON envelope must fit OkHttp's 16 MiB WebSocket send queue.
internal const val MAX_FILE_UPLOAD_BYTES = 10L * 1024 * 1024

internal fun fileUploadPath(directory: String, filename: String): String {
    if (filename.isBlank() || filename == "." || filename == ".." ||
        filename.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        throw IOException("The selected file has an invalid name")
    }
    if (!directory.startsWith('/') || directory.split('/').any { it == ".." }) {
        throw IOException("The upload destination must be an absolute directory path")
    }
    return "${directory.trimEnd('/')}/$filename"
}

/** Read before sending so a missing, oversized or unreadable source never writes a remote file. */
internal suspend fun sendFileUpload(
    path: String,
    messages: Flow<Map<String, Any?>>,
    openSource: () -> InputStream?,
    writeFile: (String) -> Boolean,
    maxBytes: Long = MAX_FILE_UPLOAD_BYTES,
) {
    val source = openSource() ?: throw IOException("Unable to open the selected file")
    val encoded = ByteArrayOutputStream()
    source.use { input ->
        Base64.getEncoder().wrap(encoded).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Files larger than 10 MB cannot be uploaded")
                output.write(buffer, 0, count)
            }
        }
    }
    currentCoroutineContext().ensureActive()
    val content = encoded.toString(Charsets.US_ASCII.name())
    val response = withTimeout(120_000L) {
        // Subscribe before sending: an acknowledgement can arrive immediately.
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            messages.first { it["type"] == "file-written" && it["path"] == path }
        }
        if (!writeFile(content)) throw IOException("Unable to send the file. Check your server connection")
        pending.await()
    }
    if (response["success"] != true) {
        throw IOException(response["error"] as? String ?: "The server could not save the file")
    }
}
