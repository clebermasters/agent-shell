package com.agentshell.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.agentshell.data.model.IncomingShare
import com.agentshell.data.model.SharedAttachment
import com.agentshell.data.model.SharedDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Copy temporary content grants before leaving the receiving activity. Never upload on receipt. */
@Singleton
class SharedContentRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val root get() = File(context.filesDir, "shared_drafts")

    suspend fun import(incoming: IncomingShare): SharedDraft = withContext(Dispatchers.IO) {
        val directory = directory(incoming.id)
        load(incoming.id)?.let { return@withContext it }
        directory.mkdirs()
        try {
            var total = 0L
            val names = mutableSetOf<String>()
            val attachments = incoming.uris.mapIndexed { index, value ->
                currentCoroutineContext().ensureActive()
                val uri = Uri.parse(value)
                val name = uniqueName(displayName(uri), names)
                val mime = context.contentResolver.getType(uri)
                    ?: incoming.mimeType?.takeUnless { '*' in it } ?: "application/octet-stream"
                val file = File(directory, "attachment-$index")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to read $name")
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_FILE_UPLOAD_BYTES) throw IOException("Shared files must total 10 MB or less")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                SharedAttachment(Uri.fromFile(file).toString(), name, mime, file.length())
            }
            val attachment = when (attachments.size) {
                0 -> null
                1 -> attachments.single()
                else -> {
                    val archive = File(directory, "shared-files.zip")
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        for (item in attachments) {
                            currentCoroutineContext().ensureActive()
                            zip.putNextEntry(ZipEntry(item.filename))
                            File(Uri.parse(item.uri).path!!).inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    if (archive.length() > MAX_FILE_UPLOAD_BYTES) throw IOException("Shared files must total 10 MB or less")
                    attachments.forEach { File(Uri.parse(it.uri).path!!).delete() }
                    SharedAttachment(Uri.fromFile(archive).toString(), "shared-files.zip", "application/zip", archive.length())
                }
            }
            val draft = SharedDraft(incoming.id, incoming.text, attachment, attachments.map { it.filename })
            File(directory, "draft.json").writeText(Json.encodeToString(draft))
            // Expire abandoned drafts without touching any current share.
            root.listFiles()?.filter { it != directory && it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L }
                ?.forEach { it.deleteRecursively() }
            draft
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun load(id: String): SharedDraft? = withContext(Dispatchers.IO) {
        val file = File(directory(id), "draft.json")
        if (!file.exists()) null else Json.decodeFromString<SharedDraft>(file.readText())
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) { directory(id).deleteRecursively(); Unit }

    private fun directory(id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid shared draft" }
        return File(root, id)
    }

    private fun displayName(uri: Uri): String {
        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { index -> index >= 0 }
                ?.let(it::getString) else null
        }.orEmpty()
        return raw.replace(Regex("[/\\\\\\x00-\\x1f]"), "_").take(200)
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "shared-file"
    }

    private fun uniqueName(name: String, names: MutableSet<String>): String {
        var candidate = name
        var suffix = 2
        while (!names.add(candidate)) candidate = "${suffix++}-$name"
        return candidate
    }
}

internal fun mergeSharedText(existing: String, shared: String): String = when {
    shared.isBlank() -> existing
    existing.isBlank() -> shared
    else -> "$existing\n\n$shared"
}

/** Bound actual bytes, including providers that omit or lie about file size. */
internal suspend fun encodeChatAttachment(source: InputStream?, maxBytes: Long = MAX_FILE_UPLOAD_BYTES): String {
    val input = source ?: throw IOException("Unable to open the attachment")
    val encoded = ByteArrayOutputStream()
    input.use {
        Base64.getEncoder().wrap(encoded).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Attachments must be 10 MB or less")
                output.write(buffer, 0, count)
            }
        }
    }
    return encoded.toString(Charsets.US_ASCII.name())
}
