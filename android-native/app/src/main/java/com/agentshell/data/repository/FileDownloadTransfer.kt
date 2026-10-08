package com.agentshell.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.util.Base64

/** Uses the binary file protocol without sending download responses into preview state. */
internal suspend fun receiveFileDownload(
    path: String,
    messages: Flow<Map<String, Any?>>,
    requestFile: () -> Unit,
    openDestination: () -> OutputStream?,
) {
    val response = withTimeout(120_000L) {
        // Subscribe before sending: a small file may arrive immediately.
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            messages.first { it["type"] == "binary-file-content" && it["path"] == path }
        }
        requestFile()
        pending.await()
    }
    (response["error"] as? String)?.let { throw IOException(it) }
    val content = response["contentBase64"] as? String
        ?: throw IOException("The server returned no file content")

    val output = openDestination() ?: throw IOException("Unable to open the selected destination")
    output.use { destination ->
        // Decode directly into the document instead of allocating another full decoded file in memory.
        Base64.getDecoder().wrap(content.byteInputStream(Charsets.US_ASCII)).use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
            }
        }
    }
}
