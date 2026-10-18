package com.agentshell.data.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.model.TranscriptionJob
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TranscriptionQueueServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val source = TranscriptionJob.Source.BROWSER
    private val target = "host/session/runtime/viewer"

    @Before fun clearQueue() {
        File(app.filesDir, "transcription_queue").deleteRecursively()
    }

    @Test fun aLateFatalErrorPublishesTheOriginalBrowserTarget() = runTest {
        var attempts = 0
        val queue = TranscriptionQueueService(app, backgroundScope) { _, _ ->
            if (++attempts == 1) TranscriptionResult.RetryableError("Offline")
            else TranscriptionResult.FatalError("Invalid API key")
        }
        val results = mutableListOf<TranscriptionQueueService.PendingResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.resultEvents.toList(results) }
        queue.enqueue(audio(), "unused-test-key", source, target)
        runCurrent()
        assertTrue(results.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        val failure = results.single() as TranscriptionQueueService.PendingResult.Failed
        assertEquals(target, failure.sourceSessionId)
        assertEquals(source, failure.source)
        assertEquals("Invalid API key", failure.error)
        assertEquals(failure.jobId, queue.jobs.value.single().id)
        assertEquals(TranscriptionJob.Status.FAILED, queue.jobs.value.single().status)
        // Legacy pending results announce immediate fatal errors only.
        assertNull(queue.pendingResult.value)
    }

    @Test fun successAfterRetryEmitsOneCompletionAndPreservesTheLegacySuccessStream() = runTest {
        var attempts = 0
        val queue = TranscriptionQueueService(app, backgroundScope) { _, _ ->
            if (++attempts == 1) TranscriptionResult.RetryableError("Offline")
            else TranscriptionResult.Success("Hello 世界")
        }
        val results = mutableListOf<TranscriptionQueueService.PendingResult>()
        val successes = mutableListOf<TranscriptionSuccessEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.resultEvents.toList(results) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.successEvents.toList(successes) }
        queue.enqueue(audio(), "unused-test-key", source, target)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        val success = results.single() as TranscriptionQueueService.PendingResult.Success
        assertEquals("Hello 世界", success.text)
        assertEquals(target, success.sourceSessionId)
        assertEquals(success.jobId, successes.single().jobId)
        assertTrue(queue.jobs.value.isEmpty())
        assertNull(queue.pendingResult.value)
    }

    @Test fun anExhaustedRetryPublishesAFailureInsteadOfLeavingTheBrowserBusy() = runTest {
        val queue = TranscriptionQueueService(app, backgroundScope) { _, _ -> TranscriptionResult.RetryableError("Offline") }
        val results = mutableListOf<TranscriptionQueueService.PendingResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.resultEvents.toList(results) }
        queue.enqueue(audio(), "unused-test-key", source, target)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        val failure = results.single() as TranscriptionQueueService.PendingResult.Failed
        assertEquals(target, failure.sourceSessionId)
        assertEquals("Offline", failure.error)
    }

    @Test fun clearingAnObservedResultDoesNotEraseANewerResult() = runTest {
        val queue = TranscriptionQueueService(app, backgroundScope) { _, _ -> TranscriptionResult.Success("dictated") }
        queue.enqueue(audio(), "unused-test-key", source, target)
        runCurrent()
        val first = queue.pendingResult.value!!
        queue.enqueue(audio(), "unused-test-key", TranscriptionJob.Source.CHAT, "conversation")
        runCurrent()
        val second = queue.pendingResult.value!!
        assertNotEquals(first.jobId, second.jobId)
        queue.clearPendingResult(first)
        assertEquals(second, queue.pendingResult.value)
        queue.clearPendingResult(second)
        assertNull(queue.pendingResult.value)
    }

    @Test fun missingAudioAndCopyFailuresHaveContextualCompletionEvents() = runTest {
        var uploads = 0
        val queue = TranscriptionQueueService(app, backgroundScope) { _, _ ->
            uploads++
            TranscriptionResult.Success("Recovered recording")
        }
        val results = mutableListOf<TranscriptionQueueService.PendingResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.resultEvents.toList(results) }
        queue.enqueue(File(app.cacheDir, "absent.m4a").absolutePath, "unused-test-key", source, target)
        runCurrent()
        assertEquals(target, results.single().sourceSessionId)
        assertTrue(results.single() is TranscriptionQueueService.PendingResult.Failed)
        val queueDirectory = File(app.filesDir, "transcription_queue")
        queueDirectory.deleteRecursively()
        queueDirectory.writeText("This file prevents persistent audio storage")
        val recording = audio()
        queue.enqueue(recording, "unused-test-key", source, target)
        runCurrent()
        assertEquals(2, results.size)
        assertEquals(target, results.last().sourceSessionId)
        assertTrue(results.last() is TranscriptionQueueService.PendingResult.Failed)
        assertEquals(0, uploads)
        assertTrue("Copy failure preserves the original recording", File(recording).exists())
        queueDirectory.delete()
        assertTrue(queueDirectory.mkdirs())
        queue.enqueue(recording, "unused-test-key", source, target)
        runCurrent()
        assertEquals(1, uploads)
        assertTrue(results.last() is TranscriptionQueueService.PendingResult.Success)
        assertFalse(File(recording).exists())
        assertTrue(queueDirectory.listFiles().orEmpty().none { it.extension == "m4a" })
    }

    private fun audio(): String = File.createTempFile("transcription-test-", ".m4a", app.cacheDir)
        .apply { writeText("test recording") }.absolutePath
}
