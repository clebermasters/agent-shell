package com.agentshell.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64

class FileDownloadTransferTest {
    private val path = "/tmp/files/binary file #1.unknown"

    private fun response(
        filePath: String = path,
        content: String? = "",
        error: String? = null,
    ): Map<String, Any?> = mapOf(
        "type" to "binary-file-content",
        "path" to filePath,
        "contentBase64" to content,
        "error" to error,
    )

    @Test
    fun downloadsExactBinaryBytesAndIgnoresUnrelatedResponses() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 3)
        val bytes = ByteArray(256 * 100) { it.toByte() }
        val output = ByteArrayOutputStream()

        receiveFileDownload(path, events, requestFile = {
            assertTrue(events.subscriptionCount.value > 0)
            assertTrue(events.tryEmit(response(filePath = "/tmp/other.txt", content = "d3Jvbmc=")))
            assertTrue(events.tryEmit(mapOf("type" to "files-list", "path" to path)))
            assertTrue(events.tryEmit(response(content = Base64.getEncoder().encodeToString(bytes))))
        }, openDestination = { output })

        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun emptyFileCreatesAndClosesAnEmptyDocument() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        receiveFileDownload(path, events, { events.tryEmit(response()) }, { output })
        assertEquals(0, output.size())
        assertTrue(closed)
    }

    @Test
    fun serverErrorDoesNotWriteADocument() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        try {
            receiveFileDownload(path, events, { events.tryEmit(response(error = "Permission denied")) }, {
                fail("Should not open the destination for an error response")
                null
            })
            fail("Expected a download failure")
        } catch (e: IOException) {
            assertEquals("Permission denied", e.message)
        }
    }

    @Test
    fun missingContentIsNotTreatedAsAnEmptyFile() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        try {
            receiveFileDownload(path, events, { events.tryEmit(response(content = null)) }, {
                fail("Should not open the destination without content")
                null
            })
            fail("Expected a download failure")
        } catch (e: IOException) {
            assertEquals("The server returned no file content", e.message)
        }
    }

    @Test
    fun nullDestinationFailsInsteadOfReportingSuccess() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        try {
            receiveFileDownload(path, events, { events.tryEmit(response()) }, { null })
            fail("Expected a destination failure")
        } catch (e: IOException) {
            assertEquals("Unable to open the selected destination", e.message)
        }
    }

    @Test
    fun storageFailureClosesTheDocumentAndPropagates() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Storage full") }
            override fun close() { closed = true }
        }
        try {
            receiveFileDownload(path, events, { events.tryEmit(response(content = "ZmlsZQ==")) }, { output })
            fail("Expected a storage failure")
        } catch (e: IOException) {
            assertEquals("Storage full", e.message)
            assertTrue(closed)
        }
    }

    @Test
    fun invalidBase64FailsAndClosesTheDocument() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        try {
            receiveFileDownload(path, events, { events.tryEmit(response(content = "!invalid!")) }, { output })
            fail("Expected a decoding failure")
        } catch (_: IOException) {
            assertTrue(closed)
        }
    }

    @Test
    fun noResponseTimesOutAndUnsubscribes() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>()
        try {
            receiveFileDownload(path, events, {}, { ByteArrayOutputStream() })
            fail("Expected a timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(0, events.subscriptionCount.value)
        }
    }

    @Test
    fun cancellingDoesNotSaveALateResponse() = runTest {
        val events = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 1)
        var opened = false
        val transfer = async(start = CoroutineStart.UNDISPATCHED) {
            receiveFileDownload(path, events, {}, {
                opened = true
                ByteArrayOutputStream()
            })
        }
        transfer.cancelAndJoin()
        events.emit(response())
        assertFalse(opened)
        assertEquals(0, events.subscriptionCount.value)
    }
}
