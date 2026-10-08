package com.agentshell.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

class FileUploadTransferTest {
    private val path = "/tmp/files/binary file #1.unknown"

    private fun response(
        filePath: String = path,
        success: Boolean? = true,
        error: String? = null,
    ): Map<String, Any?> = mapOf(
        "type" to "file-written", "path" to filePath, "success" to success, "error" to error,
    )

    @Test
    fun uploadsExactBinaryBytesAndWaitsForTheMatchingAcknowledgement() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 3)
        val bytes = ByteArray(256 * 100) { it.toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true }
        }
        sendFileUpload(path, events, { input }, { encoded ->
            assertTrue(closed)
            assertArrayEquals(bytes, Base64.getDecoder().decode(encoded))
            assertTrue(events.subscriptionCount.value > 0)
            assertTrue(events.tryEmit(response(filePath = "/tmp/other.txt", success = false)))
            assertTrue(events.tryEmit(mapOf("type" to "binary-file-content", "path" to path)))
            events.tryEmit(response())
        })
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun supportsEmptyFiles() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf()) }, { encoded ->
            assertEquals("", encoded)
            events.tryEmit(response())
        })
    }

    @Test
    fun permitsTheExactSizeLimit() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        val bytes = byteArrayOf(0, 1, 2, 3)
        sendFileUpload(path, events, { ByteArrayInputStream(bytes) }, { encoded ->
            assertArrayEquals(bytes, Base64.getDecoder().decode(encoded))
            events.tryEmit(response())
        }, maxBytes = 4)
    }

    @Test
    fun rejectsOversizedSourcesWithoutSendingAndClosesTheStream() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>()
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(5)) {
            override fun close() { closed = true }
        }
        try {
            sendFileUpload(path, events, { input }, {
                fail("An oversized file must not be sent")
                true
            }, maxBytes = 4)
            fail("Expected a size failure")
        } catch (_: IOException) {
            assertTrue(closed)
            assertEquals(0, events.subscriptionCount.value)
        }
    }

    @Test
    fun unreadableSourceDoesNotWriteAPartialFile() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Read permission denied")
            override fun close() { closed = true }
        }
        try {
            sendFileUpload(path, events, { input }, {
                fail("An unreadable file must not be sent")
                true
            })
            fail("Expected a read failure")
        } catch (e: IOException) {
            assertEquals("Read permission denied", e.message)
            assertTrue(closed)
        }
    }

    @Test
    fun missingSourceFailsBeforeSending() = runTest {
        try {
            sendFileUpload(path, MutableSharedFlow(), { null }, {
                fail("A missing file must not be sent")
                true
            })
            fail("Expected a source failure")
        } catch (e: IOException) {
            assertEquals("Unable to open the selected file", e.message)
        }
    }

    @Test
    fun propagatesServerWriteErrors() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        try {
            sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf(1)) }, {
                events.tryEmit(response(success = false, error = "Permission denied"))
            })
            fail("Expected a server failure")
        } catch (e: IOException) {
            assertEquals("Permission denied", e.message)
        }
    }

    @Test
    fun missingSuccessIsNotReportedAsAnUpload() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        try {
            sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf(1)) }, {
                events.tryEmit(response(success = null))
            })
            fail("Expected a malformed response failure")
        } catch (e: IOException) {
            assertEquals("The server could not save the file", e.message)
        }
    }

    @Test
    fun rejectedSendFailsImmediatelyAndUnsubscribes() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>()
        try {
            sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf(1)) }, { false })
            fail("Expected a send failure")
        } catch (_: IOException) {
            assertEquals(0, events.subscriptionCount.value)
        }
    }

    @Test
    fun noAcknowledgementTimesOutAndUnsubscribes() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>()
        try {
            sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf(1)) }, { true })
            fail("Expected a timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(0, events.subscriptionCount.value)
        }
    }

    @Test
    fun cancellingUnsubscribesFromLateAcknowledgements() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        val transfer = async(start = CoroutineStart.UNDISPATCHED) {
            sendFileUpload(path, events, { ByteArrayInputStream(byteArrayOf(1)) }, { true })
        }
        transfer.cancelAndJoin()
        events.emit(response())
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun ignoresResponsesFromADifferentServer() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 2)
        sendFileUpload(
            path,
            events.filter { it["_sourceServerUrl"] == "ws://original" },
            { ByteArrayInputStream(byteArrayOf(1)) },
            {
                events.tryEmit(response(success = false) + ("_sourceServerUrl" to "ws://other"))
                events.tryEmit(response() + ("_sourceServerUrl" to "ws://original"))
            },
        )
    }

    @Test
    fun uploadNamesStayWithinTheSelectedDirectory() {
        assertEquals("/report #1.txt", fileUploadPath("/", "report #1.txt"))
        assertEquals("/tmp/folder/report.txt", fileUploadPath("/tmp/folder/", "report.txt"))
        for (name in listOf("", " ", ".", "..", "../file", "nested/file", "nested\\file", "bad\u0000name")) {
            assertThrows(IOException::class.java) { fileUploadPath("/tmp/folder", name) }
        }
        assertThrows(IOException::class.java) { fileUploadPath("relative", "file.txt") }
        assertThrows(IOException::class.java) { fileUploadPath("/tmp/../etc", "file.txt") }
    }
}
