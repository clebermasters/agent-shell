package com.agentshell.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.agentshell.core.power.BackgroundWorkPolicy
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.chatFingerprint
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineAttachmentRetryTest {
    private lateinit var context: Application
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val policy = BackgroundWorkPolicy().apply { updateDevice(connected = false) }
    private val now = AtomicLong(1_700_000_000_000L)
    private val token = AtomicReference("first-token")
    private val stores = mutableListOf<OfflineAttachmentStore>()
    private val retry = AttachmentRetryPolicy(jitter = { 0L })
    private val base get() = server.url("/api/chat/files").toString()
    private fun message(id: String, size: Long? = null) = ChatMessage(id, "assistant", null, 1,
        blocks = listOf(ChatBlock("file", id = id, sizeBytes = size)))
    private fun newStore() = OfflineAttachmentStore(context, client, policy, clock = now::get,
        retryPolicy = retry, credential = token::get).also(stores::add)
    private suspend fun await(condition: () -> Boolean) {
        withTimeout(8_000) { while (!condition()) delay(10) }
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        server.start()
    }

    @After fun cleanup() {
        stores.forEach { it.close() }
        server.close()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun duplicateEnqueueCannotBypassAFailedFilesDeadline() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val store = newStore()
        store.enqueue(base, listOf(message("failed")))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().singleOrNull()?.outcome == AttachmentOutcome.RETRY }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val due = store.pendingJobs().single().nextAttemptAtMillis
        repeat(1_000) { store.enqueue(base, listOf(message("failed"))) }
        store.requestDueWork()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(due, store.pendingJobs().single().nextAttemptAtMillis)
        assertEquals(1, store.pendingJobs().single().attempts)
        assertEquals(1, server.requestCount)
    }

    @Test fun partialAttachmentMetadataCannotEraseKnownMetadataAndResetBackoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val store = newStore()
        store.enqueue(base, listOf(message("partial-metadata", 10)))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().singleOrNull()?.outcome == AttachmentOutcome.UNAVAILABLE }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val due = store.pendingJobs().single().nextAttemptAtMillis
        repeat(100) {
            store.enqueue(base, listOf(message("partial-metadata")))
            store.enqueue(base, listOf(message("partial-metadata", 10)))
        }
        store.requestDueWork()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(10L, store.pendingJobs().single().sourceSizeBytes)
        assertEquals(due, store.pendingJobs().single().nextAttemptAtMillis)
        assertEquals(1, store.pendingJobs().single().attempts)
    }

    @Test fun retryEligibilitySurvivesRestartAndLaterCompletesWithoutAnotherEnqueue() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("durable attachment"))
        val first = newStore()
        first.enqueue(base, listOf(message("restart")))
        policy.updateDevice(connected = true)
        await { first.pendingJobs().singleOrNull()?.outcome == AttachmentOutcome.RETRY }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val due = first.pendingJobs().single().nextAttemptAtMillis
        first.close()
        val second = newStore()
        second.requestDueWork()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        now.set(due)
        second.requestDueWork()
        await { OfflineAttachmentStore.cachedFile(context, "$base/restart") != null }
        assertEquals("durable attachment", OfflineAttachmentStore.cachedFile(context, "$base/restart")!!.readText())
        assertEquals(2, server.requestCount)
        assertTrue(second.pendingJobs().isEmpty())
    }

    @Test fun aLargeFailingBacklogIsBoundedAndNewJobsDoNotStarveBehindRetries() = runBlocking {
        val requested = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.path.orEmpty()
                return MockResponse().setResponseCode(503)
            }
        }
        var store = newStore()
        store.enqueue(base, (0 until 40).map { message("file-$it") })
        policy.updateDevice(connected = true)
        await { server.requestCount == 8 && store.pendingJobs().count { it.outcome == AttachmentOutcome.RETRY } == 8 }
        repeat(100) { store.requestDueWork() }
        delay(100)
        assertEquals(8, server.requestCount)
        store.close()
        store = newStore()
        store.requestDueWork()
        delay(100)
        assertEquals("Process restart must preserve the batch budget too", 8, server.requestCount)
        for (window in 2..5) {
            now.addAndGet(60_000)
            store.requestDueWork()
            await { server.requestCount == window * 8 && store.pendingJobs().none { it.outcome == AttachmentOutcome.IN_FLIGHT } }
        }
        assertEquals(40, requested.size)
        assertEquals("Untouched jobs are served before retrying an earlier failed job", 40, requested.toSet().size)
        assertTrue(store.pendingJobs().all { it.attempts == 1 })
    }

    @Test fun stricterPoliciesExtendTheExistingWindowAndSurviveRestart() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        val began = now.get()
        policy.activityStarted()
        var store = newStore()
        store.enqueue(base, (0 until 30).map { message("transition-$it") })
        policy.updateDevice(connected = true)
        await { server.requestCount == 24 && store.pendingJobs().none { it.outcome == AttachmentOutcome.IN_FLIGHT } }
        fun queue() = Json.decodeFromString<AttachmentQueueState>(File(context.filesDir,
            "offline_chat_files/pending/queue-state.json").readText())
        assertEquals(began, queue().windowStartedAtMillis)
        now.set(began + 15_001) // The former foreground window has expired.
        policy.activityStopped()
        await { queue().windowEndsAtMillis == began + 60_000 }
        assertEquals(24, queue().attemptsInWindow)
        assertEquals(24, server.requestCount)
        policy.updateDevice(interactive = false, metered = true)
        await { queue().windowEndsAtMillis == began + 300_000 }
        assertEquals(24, queue().attemptsInWindow)
        store.close()
        store = newStore()
        now.set(began + 299_999)
        store.requestDueWork()
        delay(150)
        assertEquals("A worker restart must not reset the stricter screen-off budget", 24, server.requestCount)
        assertEquals(began, queue().windowStartedAtMillis)
        assertEquals(began + 300_000, queue().windowEndsAtMillis)
        assertEquals(24, queue().attemptsInWindow)
        now.incrementAndGet()
        store.requestDueWork()
        await { server.requestCount == 28 && store.pendingJobs().none { it.outcome == AttachmentOutcome.IN_FLIGHT } }
        assertEquals(4, queue().attemptsInWindow)
    }

    @Test fun authFailurePausesOnlyItsOriginAndNewCredentialsRecoverItsDurableJobs() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.getHeader("X-Auth-Token").orEmpty()
                return if (request.getHeader("X-Auth-Token") == "first-token") MockResponse().setResponseCode(401)
                    else MockResponse().setBody("recovered")
            }
        }
        val store = newStore()
        store.enqueue(base, listOf(message("first"), message("second")))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().any { it.outcome == AttachmentOutcome.AUTH_REQUIRED } }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        store.requestDueWork()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        token.set("new-token")
        store.onCredentialsChanged()
        await { store.pendingJobs().isEmpty() }
        assertEquals(listOf("first-token", "new-token", "new-token"), requests.toList())
        assertEquals("recovered", OfflineAttachmentStore.cachedFile(context, "$base/first")!!.readText())
    }

    @Test fun anAuthBlockedOriginDoesNotBlockAnotherHostsAutomaticArchive() = runBlocking {
        val other = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        other.enqueue(MockResponse().setBody("other host bytes"))
        other.start()
        val otherBase = other.url("/api/chat/files").toString()
        val store = newStore()
        try {
            store.enqueue(base, listOf(message("same-id")))
            store.enqueue(otherBase, listOf(message("same-id")))
            policy.updateDevice(connected = true)
            await { store.pendingJobs().any { it.outcome == AttachmentOutcome.AUTH_REQUIRED } &&
                OfflineAttachmentStore.cachedFile(context, "$otherBase/same-id") != null }
            assertEquals(1, server.requestCount)
            assertEquals(1, other.requestCount)
            assertNull(OfflineAttachmentStore.cachedFile(context, "$base/same-id"))
            assertEquals("other host bytes", OfflineAttachmentStore.cachedFile(context, "$otherBase/same-id")!!.readText())
        } finally { other.close() }
    }

    @Test fun retryAfterThrottlesTheWholeOriginAndManualRetryAffectsOnlyOneJob() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "600"))
        server.enqueue(MockResponse().setBody("manual recovery"))
        server.enqueue(MockResponse().setBody("automatic recovery"))
        val store = newStore()
        store.enqueue(base, listOf(message("a"), message("b")))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().any { it.outcome == AttachmentOutcome.RATE_LIMITED } }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        now.addAndGet(60_000)
        store.requestDueWork()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        store.retry("$base/a")
        await { OfflineAttachmentStore.cachedFile(context, "$base/a") != null }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        now.addAndGet(540_000)
        store.requestDueWork()
        await { OfflineAttachmentStore.cachedFile(context, "$base/b") != null }
        assertEquals(3, server.requestCount)
    }

    @Test fun a503RetryAfterPausesEveryJobAtThatOriginButOtherOriginsProgress() = runBlocking {
        val ready = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (ready.get()) MockResponse().setBody("available")
                else MockResponse().setResponseCode(503).setHeader("Retry-After", "600")
        }
        val other = MockWebServer()
        other.enqueue(MockResponse().setBody("unrelated host"))
        other.start()
        val otherBase = other.url("/api/chat/files").toString()
        val store = newStore()
        try {
            store.enqueue(base, listOf(message("a"), message("b")))
            store.enqueue(otherBase, listOf(message("other")))
            policy.updateDevice(connected = true)
            await { server.requestCount == 1 && store.pendingJobs().any { it.outcome == AttachmentOutcome.RETRY } &&
                OfflineAttachmentStore.cachedFile(context, "$otherBase/other") != null }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            now.addAndGet(599_999)
            store.requestDueWork()
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(1, other.requestCount)
            assertEquals(2, store.pendingJobs().size)
            ready.set(true)
            now.incrementAndGet()
            store.requestDueWork()
            await { store.pendingJobs().isEmpty() }
            assertEquals(3, server.requestCount)
            assertEquals("available", OfflineAttachmentStore.cachedFile(context, "$base/a")!!.readText())
            assertEquals("available", OfflineAttachmentStore.cachedFile(context, "$base/b")!!.readText())
        } finally { other.close() }
    }

    @Test fun aChangedAttachmentReferenceRecoversAnUnavailableJobWithoutDeletingOtherFiles() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("restored bytes"))
        val store = newStore()
        store.enqueue(base, listOf(message("missing", 10)))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().singleOrNull()?.outcome == AttachmentOutcome.UNAVAILABLE }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        store.enqueue(base, listOf(message("missing", 10)))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        val deadline = store.pendingJobs().single().nextAttemptAtMillis
        assertTrue(deadline > now.get() + 6 * 60 * 60_000)
        store.enqueue(base, listOf(message("missing", "restored bytes".toByteArray().size.toLong())))
        await { OfflineAttachmentStore.cachedFile(context, "$base/missing") != null }
        assertEquals("restored bytes", OfflineAttachmentStore.cachedFile(context, "$base/missing")!!.readText())
        assertEquals(2, server.requestCount)
    }

    @Test fun oldPlainUrlJobsMigrateAtomicallyAndStillDownloadAutomatically() = runBlocking {
        val url = "$base/legacy"
        val pending = File(context.filesDir, "offline_chat_files/pending").apply { mkdirs() }
        val old = File(pending, "${chatFingerprint(url)}.job").apply { writeText(url) }
        val store = newStore()
        assertEquals(url, store.pendingJobs().single().url)
        assertTrue(old.readText().startsWith("{"))
        server.enqueue(MockResponse().setBody("legacy bytes"))
        policy.updateDevice(connected = true)
        await { OfflineAttachmentStore.cachedFile(context, url) != null }
        assertEquals("legacy bytes", OfflineAttachmentStore.cachedFile(context, url)!!.readText())
        assertFalse(old.exists())
    }

    @Test fun meteredBackgroundWorkStillCompletesAutomaticallyInBoundedBatches() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("cached")
        }
        policy.updateDevice(metered = true, interactive = false)
        val store = newStore()
        store.enqueue(base, (0 until 6).map { message("metered-$it") })
        policy.updateDevice(connected = true)
        await { server.requestCount == 4 && store.pendingJobs().size == 2 }
        now.addAndGet(299_999)
        store.requestDueWork()
        delay(100)
        assertEquals(4, server.requestCount)
        now.incrementAndGet()
        store.requestDueWork()
        await { store.pendingJobs().isEmpty() }
        assertEquals(6, server.requestCount)
        assertTrue((0 until 6).all { OfflineAttachmentStore.cachedFile(context, "$base/metered-$it") != null })
    }

    @Test fun anOversizeFileFinishesBeforeTheByteBudgetDefersTheNextJob() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024)))
        server.enqueue(MockResponse().setBody("next file"))
        val store = newStore()
        store.enqueue(base, listOf(message("a-large"), message("b-next")))
        policy.updateDevice(connected = true)
        await { OfflineAttachmentStore.cachedFile(context, "$base/a-large") != null }
        assertEquals(2L * 1024 * 1024, OfflineAttachmentStore.cachedFile(context, "$base/a-large")!!.length())
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        now.addAndGet(60_000)
        store.requestDueWork()
        await { OfflineAttachmentStore.cachedFile(context, "$base/b-next") != null }
        assertEquals(2, server.requestCount)
    }

    @Test fun closingTheStoreCancelsTheActualHttpCallAndRetainsSafeRetryState() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val store = newStore()
        store.enqueue(base, listOf(message("stalled")))
        policy.updateDevice(connected = true)
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        store.close()
        await { client.dispatcher.runningCallsCount() == 0 }
        assertNull(OfflineAttachmentStore.cachedFile(context, "$base/stalled"))
        assertEquals(AttachmentOutcome.IN_FLIGHT, store.pendingJobs().single().outcome)
        assertTrue(store.pendingJobs().single().nextAttemptAtMillis > now.get())
        assertFalse(File(context.filesDir, "offline_chat_files").listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun cancellingAPartialBodyNeverPublishesPartialCacheBytes() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 1, TimeUnit.SECONDS))
        val store = newStore()
        store.enqueue(base, listOf(message("partial")))
        policy.updateDevice(connected = true)
        await { File(context.filesDir, "offline_chat_files").listFiles().orEmpty().any { it.extension == "part" && it.length() > 0 } }
        store.close()
        await { client.dispatcher.runningCallsCount() == 0 }
        assertNull(OfflineAttachmentStore.cachedFile(context, "$base/partial"))
        assertFalse(File(context.filesDir, "offline_chat_files").listFiles().orEmpty().any { it.extension == "part" })
        assertEquals(1, store.pendingJobs().size)
    }

    @Test fun successfulUnknownAndKnownZeroSizeEmptyFilesAreCachedAndNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody(""))
        val store = newStore()
        val messages = listOf(message("empty-unknown"), message("empty-zero", 0))
        store.enqueue(base, messages)
        policy.updateDevice(connected = true)
        await { messages.all { OfflineAttachmentStore.cachedFile(context, "$base/${it.id}") != null } }
        store.enqueue(base, messages)
        assertTrue(store.pendingJobs().isEmpty())
        assertTrue(messages.all { OfflineAttachmentStore.cachedFile(context, "$base/${it.id}")!!.length() == 0L })
        assertEquals(2, server.requestCount)
    }

    @Test fun emptyOrShortSuccessfulResponsesCannotPermanentlyCompleteAKnownSizeFile() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("abc"))
        server.enqueue(MockResponse().setBody("good"))
        val store = newStore()
        store.enqueue(base, listOf(message("expected-four", 4)))
        policy.updateDevice(connected = true)
        await { store.pendingJobs().singleOrNull()?.let { it.attempts == 1 && it.outcome == AttachmentOutcome.RETRY } == true }
        assertNull(OfflineAttachmentStore.cachedFile(context, "$base/expected-four"))
        now.set(store.pendingJobs().single().nextAttemptAtMillis)
        store.requestDueWork()
        await { store.pendingJobs().singleOrNull()?.let { it.attempts == 2 && it.outcome == AttachmentOutcome.RETRY } == true }
        assertNull(OfflineAttachmentStore.cachedFile(context, "$base/expected-four"))
        assertFalse(File(context.filesDir, "offline_chat_files").listFiles().orEmpty().any { it.extension == "part" })
        now.set(store.pendingJobs().single().nextAttemptAtMillis)
        store.requestDueWork()
        await { OfflineAttachmentStore.cachedFile(context, "$base/expected-four") != null }
        assertEquals("good", OfflineAttachmentStore.cachedFile(context, "$base/expected-four")!!.readText())
        assertEquals(3, server.requestCount)
        assertTrue(store.pendingJobs().isEmpty())
    }
}
