package com.agentshell.data.repository

import android.content.Context
import com.agentshell.core.config.BuildConfig
import com.agentshell.core.diagnostics.BatteryDiagnostics
import com.agentshell.core.power.BackgroundWorkPolicy
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.chatFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/** Attachment bytes live in filesDir so Android's temporary-cache cleanup cannot evict them. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineAttachmentStore internal constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val workPolicy: BackgroundWorkPolicy,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryPolicy: AttachmentRetryPolicy = AttachmentRetryPolicy(),
    private val credential: () -> String = { BuildConfig.AUTH_TOKEN },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        workPolicy: BackgroundWorkPolicy,
    ) : this(context, client, workPolicy, System::currentTimeMillis)

    // Keep the previous JVM constructor for existing callers and tests.
    constructor(context: Context, client: OkHttpClient) : this(context, client, BackgroundWorkPolicy())

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val downloadSignals = Channel<Unit>(Channel.CONFLATED)
    private val jobsDirectory = File(context.filesDir, "offline_chat_files/pending")
    private val stateFile = File(jobsDirectory, "queue-state.json")
    private val fileLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var activeCall: Call? = null

    init {
        scope.launch {
            combine(workPolicy.state, workPolicy.automaticNetworkingAllowed) { state, allowed -> state.connected && allowed }.collect { allowed ->
                if (!allowed) activeCall?.cancel()
                requestDueWork()
            }
        }
        scope.launch {
            while (isActive) {
                val wait = try { runDueBatch() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    android.util.Log.w("OfflineAttachment", "Attachment queue could not make progress; durable jobs retained")
                    60_000L
                }
                if (wait == Long.MAX_VALUE) downloadSignals.receive()
                else withTimeoutOrNull(wait.coerceAtLeast(1_000)) { downloadSignals.receive() }
            }
        }
    }

    fun enqueue(baseUrl: String, messages: List<ChatMessage>) {
        var changed = false
        for (message in messages) for (block in message.blocks) {
            if (block.blockType !in setOf(ChatBlockType.IMAGE, ChatBlockType.AUDIO, ChatBlockType.FILE)) continue
            val id = block.id?.takeIf { it.isNotBlank() } ?: continue
            val url = "$baseUrl/$id"
            if (url.toHttpUrlOrNull() == null) continue
            if (cachedFile(context, url) != null) continue
            // Immutable content identity and attachment metadata only; chat text
            // changes must not continually reset an unavailable file's deadline.
            synchronized(fileLock) {
                val file = jobFile(url)
                val old = readJob(file)
                // Some archive representations omit metadata supplied by other
                // representations of the same attachment. Omission is not a
                // source change and must not reset a failed file's backoff.
                val size = block.sizeBytes?.takeIf { it >= 0 } ?: old?.sourceSizeBytes
                val mime = block.mimeType ?: old?.sourceMimeType
                val revision = chatFingerprint("$id:$size:$mime")
                if (old == null) {
                    writeJob(AttachmentDownloadJob(url = url, sourceRevision = revision, sourceSizeBytes = size,
                        sourceMimeType = mime, credentialRevision = credentialRevision()))
                    changed = true
                } else if (old.sourceRevision != revision || old.sourceSizeBytes != size || old.sourceMimeType != mime) {
                    val merged = old.copy(sourceRevision = revision, sourceSizeBytes = size, sourceMimeType = mime)
                    val next = if (old.sourceRevision.isEmpty() || old.sourceRevision == revision) merged
                        else merged.copy(attempts = 0, nextAttemptAtMillis = 0, outcome = AttachmentOutcome.PENDING)
                    writeJob(next)
                    changed = true
                }
            }
        }
        // A repeated copy of unchanged history is deliberately a no-op.
        if (changed) requestDueWork()
    }

    /** Explicitly retry one attachment once; never reset the rest of the backlog. */
    fun retry(url: String) {
        synchronized(fileLock) {
            val old = readJob(jobFile(url)) ?: return
            if (cachedFile(context, url) != null) return
            writeJob(old.copy(nextAttemptAtMillis = 0, attempts = 0, manual = true, outcome = AttachmentOutcome.PENDING))
        }
        requestDueWork()
    }

    /** A future runtime credential provider can signal a generation change here. */
    fun onCredentialsChanged() = requestDueWork()

    internal fun requestDueWork() { downloadSignals.trySend(Unit) }
    internal fun pendingJobs(): List<AttachmentDownloadJob> = synchronized(fileLock) { loadJobs() }
    internal fun close() { scope.cancel(); activeCall?.cancel() }

    private fun credentialRevision() = chatFingerprint(credential())
    private fun jobFile(url: String) = File(jobsDirectory, "${chatFingerprint(url)}.job")
    private fun origin(url: String): String = url.toHttpUrlOrNull()!!.let { "${it.scheme}://${it.host}:${it.port}" }

    private fun readJob(file: File): AttachmentDownloadJob? {
        if (!file.isFile) return null
        val text = file.readText()
        val job = if (text.trimStart().startsWith('{')) runCatching { json.decodeFromString<AttachmentDownloadJob>(text) }.getOrNull()
            else text.trim().takeIf { it.toHttpUrlOrNull() != null }?.let { AttachmentDownloadJob(url = it) }
        if (job == null || job.version != 1 || job.url.toHttpUrlOrNull() == null) return null
        return job
    }

    private fun loadJobs(): List<AttachmentDownloadJob> {
        val revision = credentialRevision()
        return jobsDirectory.listFiles().orEmpty().filter { it.extension == "job" }.mapNotNull { file ->
            val old = readJob(file) ?: return@mapNotNull null
            if (cachedFile(context, old.url) != null) { file.delete(); return@mapNotNull null }
            val next = when {
                old.credentialRevision.isEmpty() -> old.copy(credentialRevision = revision)
                old.credentialRevision != revision -> old.copy(credentialRevision = revision, attempts = 0,
                    nextAttemptAtMillis = 0, outcome = AttachmentOutcome.PENDING)
                else -> old
            }
            // Also migrates the old plain-URL format without deleting its job.
            if (old != next || !file.readText().trimStart().startsWith('{')) writeJob(next)
            next
        }
    }

    private fun writeJob(job: AttachmentDownloadJob) = atomicWrite(jobFile(job.url), json.encodeToString(job))
    private fun readQueue() = runCatching { json.decodeFromString<AttachmentQueueState>(stateFile.readText()) }.getOrDefault(AttachmentQueueState())
    private fun writeQueue(state: AttachmentQueueState) = atomicWrite(stateFile, json.encodeToString(state))
    private fun atomicWrite(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            check(temporary.renameTo(destination)) { "Could not persist attachment progress" }
        } finally { temporary.delete() }
    }

    private fun queueAt(now: Long): AttachmentQueueState {
        val old = readQueue()
        val revision = credentialRevision()
        val hosts = old.hosts.filterValues { it.nextAttemptAtMillis > now &&
            (it.outcome != AttachmentOutcome.AUTH_REQUIRED || it.credentialRevision == revision) }
        val budget = retryPolicy.budget(workPolicy.state.value)
        // Extend a foreground window before considering it expired under a
        // stricter policy. Otherwise a screen-off/metered transition could reset
        // the existing usage after the old 15-second foreground interval.
        val clockMovedBack = old.windowStartedAtMillis > now
        val start = old.windowStartedAtMillis.takeIf { it > 0 && it <= now } ?: now
        val priorDuration = (old.windowEndsAtMillis - old.windowStartedAtMillis).coerceIn(0, 300_000)
        val end = if (clockMovedBack) now + maxOf(priorDuration, budget.windowMillis)
            else maxOf(old.windowEndsAtMillis, start + budget.windowMillis)
        val next = if (old.windowEndsAtMillis <= 0 || now >= end) {
            AttachmentQueueState(windowEndsAtMillis = now + budget.windowMillis, windowStartedAtMillis = now, hosts = hosts)
        } else old.copy(windowStartedAtMillis = start, windowEndsAtMillis = end, hosts = hosts)
        // Persist an eligibility transition even when no new request is allowed.
        // Recreating the worker must retain the extended window and usage.
        if (next != old) writeQueue(next)
        return next
    }

    private suspend fun runDueBatch(): Long {
        if (!workPolicy.state.value.connected || !workPolicy.automaticNetworkingAllowed.value) return Long.MAX_VALUE
        val jobs = synchronized(fileLock) { loadJobs() }
            .sortedWith(compareByDescending<AttachmentDownloadJob> { it.manual }.thenBy { it.lastAttemptAtMillis }.thenBy { it.url })
        for (candidate in jobs) {
            currentCoroutineContext().ensureActive()
            if (!workPolicy.state.value.connected || !workPolicy.automaticNetworkingAllowed.value) break
            if (!candidate.manual && synchronized(fileLock) {
                val queue = queueAt(clock())
                val budget = retryPolicy.budget(workPolicy.state.value)
                queue.attemptsInWindow >= budget.attempts || queue.bytesInWindow >= budget.bytes
            }) break
            val claimed = synchronized(fileLock) {
                val now = clock()
                val job = readJob(jobFile(candidate.url)) ?: return@synchronized null
                val queue = queueAt(now)
                val budget = retryPolicy.budget(workPolicy.state.value)
                val hostDue = queue.hosts[origin(job.url)]?.nextAttemptAtMillis ?: 0
                if (!job.manual && (job.nextAttemptAtMillis > now || hostDue > now ||
                        queue.attemptsInWindow >= budget.attempts || queue.bytesInWindow >= budget.bytes)) return@synchronized null
                val next = job.copy(attempts = job.attempts + 1, lastAttemptAtMillis = now, manual = false,
                    nextAttemptAtMillis = now + retryPolicy.retryDelay(job.attempts + 1), outcome = AttachmentOutcome.IN_FLIGHT)
                writeJob(next)
                writeQueue(queue.copy(attemptsInWindow = queue.attemptsInWindow + 1))
                next
            } ?: continue
            BatteryDiagnostics.event("attachment_attempt_started")
            val result = try { download(claimed.url, claimed.sourceSizeBytes) } catch (error: CancellationException) {
                BatteryDiagnostics.event("attachment_cancelled")
                throw error
            }
            try {
                currentCoroutineContext().ensureActive()
                synchronized(fileLock) {
                    val latest = readJob(jobFile(claimed.url)) ?: return@synchronized
                    // A changed source/manual retry must not be overwritten by a stale attempt.
                    if (latest.sourceRevision != claimed.sourceRevision || latest.credentialRevision != claimed.credentialRevision || latest.manual) return@synchronized
                    val queue = queueAt(clock())
                    if (result is Downloaded) {
                        val destination = attachmentFile(context, claimed.url)
                        check(result.temporary.renameTo(destination)) { "Could not commit attachment bytes" }
                        jobFile(claimed.url).delete()
                        writeQueue(queue.copy(bytesInWindow = queue.bytesInWindow + result.bytes))
                        BatteryDiagnostics.event("attachment_download_succeeded")
                    } else if (result is Failed) {
                        val now = clock()
                        val failed = retryPolicy.failed(claimed, result.status, result.retryAfter, now)
                        writeJob(failed)
                        val serverRequestedPause = result.status == 503 && retryPolicy.retryAfterMillis(result.retryAfter, now) != null
                        val hostState = if (serverRequestedPause || failed.outcome in setOf(AttachmentOutcome.AUTH_REQUIRED, AttachmentOutcome.RATE_LIMITED))
                            queue.hosts + (origin(claimed.url) to AttachmentHostThrottle(failed.nextAttemptAtMillis, failed.outcome, claimed.credentialRevision))
                            else queue.hosts
                        writeQueue(queue.copy(hosts = hostState))
                        BatteryDiagnostics.event(when (failed.outcome) {
                            AttachmentOutcome.UNAVAILABLE -> "attachment_unavailable"
                            AttachmentOutcome.AUTH_REQUIRED -> "attachment_auth_blocked"
                            else -> "attachment_retry_scheduled"
                        })
                    }
                }
                if (result is Downloaded && cachedFile(context, claimed.url) != null) completed.emit(claimed.url)
            } finally { if (result is Downloaded) result.temporary.delete() }
        }
        return synchronized(fileLock) {
            if (!workPolicy.state.value.connected || !workPolicy.automaticNetworkingAllowed.value) return@synchronized Long.MAX_VALUE
            val now = clock()
            val queue = queueAt(now)
            val budget = retryPolicy.budget(workPolicy.state.value)
            val exhausted = queue.attemptsInWindow >= budget.attempts || queue.bytesInWindow >= budget.bytes
            loadJobs().minOfOrNull { job ->
                if (job.manual) now else maxOf(job.nextAttemptAtMillis, queue.hosts[origin(job.url)]?.nextAttemptAtMillis ?: 0,
                    if (exhausted) queue.windowEndsAtMillis else now)
            }?.let { (it - now).coerceAtLeast(1_000) } ?: Long.MAX_VALUE
        }
    }

    private sealed interface DownloadResult
    private data class Downloaded(val temporary: File, val bytes: Long) : DownloadResult
    private data class Failed(val status: Int? = null, val retryAfter: String? = null) : DownloadResult

    private suspend fun download(url: String, expectedBytes: Long?): DownloadResult = suspendCancellableCoroutine { continuation ->
        val destination = attachmentFile(context, url)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.${java.util.UUID.randomUUID()}.part")
        val call = client.newCall(Request.Builder().url(url).header("X-Auth-Token", credential()).build())
        activeCall = call
        continuation.invokeOnCancellation { call.cancel(); temporary.delete() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (activeCall === call) activeCall = null
                temporary.delete()
                if (continuation.isActive) continuation.resume(Failed())
            }
            override fun onResponse(call: Call, response: Response) {
                var handedOff = false
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!response.isSuccessful || response.body == null) {
                            continuation.resume(Failed(response.code, response.header("Retry-After")))
                        } else {
                            val count = response.body!!.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                            if (!continuation.isActive) return
                            if (expectedBytes != null && expectedBytes >= 0 && count != expectedBytes) {
                                BatteryDiagnostics.event("attachment_size_mismatch")
                                continuation.resume(Failed())
                                return
                            }
                            handedOff = true
                            continuation.resume(Downloaded(temporary, count), onCancellation = { _, _, _ -> temporary.delete() })
                        }
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(Failed())
                } finally {
                    if (activeCall === call) activeCall = null
                    if (!handedOff) temporary.delete()
                }
            }
        })
    }

    companion object {
        private val completed = MutableSharedFlow<String>(extraBufferCapacity = 16)

        /** Only the image being downloaded changes; disk/hash work stays off the UI thread. */
        fun observeUrl(context: Context, url: String): Flow<String> = channelFlow {
            // Subscribe before probing disk so a download finishing during the probe is not missed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                completed.filter { it == url }.collect { send(resolve(context, url)) }
            }
            send(resolve(context, url))
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
        private fun attachmentFile(context: Context, url: String) = File(context.filesDir, "offline_chat_files/${chatFingerprint(url)}")
        fun cachedFile(context: Context, url: String): File? = attachmentFile(context, url).takeIf { it.isFile }
        fun resolve(context: Context, url: String): String = cachedFile(context, url)?.toURI()?.toString() ?: url
    }
}
