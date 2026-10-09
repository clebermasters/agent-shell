package com.agentshell.data.repository

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.model.IncomingShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SharedContentRepositoryTest {
    private lateinit var app: Application
    private lateinit var repository: SharedContentRepository

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "shared_drafts").deleteRecursively()
        repository = SharedContentRepository(app)
    }

    private fun incoming(text: String = "", uris: List<String> = emptyList(), mime: String? = null) =
        IncomingShare(UUID.randomUUID().toString(), text, uris, mime)

    private fun register(path: String, data: ByteArray): String {
        val uri = Uri.parse("content://share.test/$path")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(data))
        return uri.toString()
    }

    @Test fun textOnlyDraftSurvivesRepositoryRecreation() = runBlocking {
        val raw = incoming("Português • https://example.com")
        val draft = repository.import(raw)
        assertEquals(raw.text, draft.text)
        assertNull(draft.attachment)
        assertEquals(draft, SharedContentRepository(app).load(raw.id))
    }

    @Test fun copiesFileBeforeTemporaryPermissionExpires() = runBlocking {
        val bytes = "shared PDF content".toByteArray()
        val raw = incoming("Read this", listOf(register("pdf", bytes)), "application/pdf")
        val draft = repository.import(raw)
        val attachment = draft.attachment!!
        assertEquals("application/pdf", attachment.mimeType)
        assertArrayEquals(bytes, File(Uri.parse(attachment.uri).path!!).readBytes())
        // Reimport uses the durable copy, rather than reopening the exhausted provider stream.
        assertEquals(draft, repository.import(raw))
    }

    @Test fun multipleFilesProduceOneZipWithUniqueSafeNames() = runBlocking {
        val raw = incoming(uris = listOf(register("a", "first".toByteArray()), register("b", "second".toByteArray())))
        val draft = repository.import(raw)
        assertEquals(2, draft.filenames.size)
        assertEquals("application/zip", draft.attachment!!.mimeType)
        ZipFile(File(Uri.parse(draft.attachment.uri).path!!)).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(2, entries.size)
            assertEquals(2, entries.map { it.name }.distinct().size)
            assertTrue(entries.all { '/' !in it.name && '\\' !in it.name })
            assertEquals(listOf("first", "second"), entries.map { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } })
        }
    }

    @Test fun oversizedSourceIsRejectedAndPartialFilesAreRemoved() = runBlocking {
        val raw = incoming(uris = listOf(register("large", ByteArray((MAX_FILE_UPLOAD_BYTES + 1).toInt()))))
        try {
            repository.import(raw)
            fail("Expected size limit")
        } catch (_: IOException) { }
        assertFalse(File(app.filesDir, "shared_drafts/${raw.id}").exists())
    }

    @Test fun discardRemovesOnlyTheChosenDraft() = runBlocking {
        val first = repository.import(incoming("first"))
        val second = repository.import(incoming("second"))
        repository.discard(first.id)
        assertNull(repository.load(first.id))
        assertEquals(second, repository.load(second.id))
    }

    @Test fun invalidDraftIdCannotEscapeStorage() = runBlocking {
        try { repository.load("../../private"); fail("Expected invalid ID") } catch (_: IllegalArgumentException) { }
    }

    @Test fun mergesSharedTextWithExistingDraftInsteadOfReplacingIt() {
        assertEquals("existing\n\nshared", mergeSharedText("existing", "shared"))
        assertEquals("existing", mergeSharedText("existing", ""))
        assertEquals("shared", mergeSharedText("", "shared"))
    }

    @Test fun attachmentEncodingPreservesBytesAndEnforcesActualSize() = runBlocking {
        val bytes = byteArrayOf(0, 1, -1, 3, 4)
        assertArrayEquals(bytes, Base64.getDecoder().decode(encodeChatAttachment(ByteArrayInputStream(bytes), 5)))
        try { encodeChatAttachment(ByteArrayInputStream(bytes), 4); fail("Expected size limit") } catch (_: IOException) { }
        try { encodeChatAttachment(null); fail("Expected unreadable source") } catch (_: IOException) { }
    }
}
