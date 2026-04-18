package com.agentshell.data.services

import android.content.Context
import com.agentshell.data.model.TranscriptionJob
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// TranscriptionQueueService
//
// Resilient transcription queue that persists audio files and retry state.
// Survives network outages, app restarts, and cache clearing.
// ---------------------------------------------------------------------------

/** Emitted when a queued transcription eventually succeeds (e.g. after retry). */
data class TranscriptionSuccessEvent(
    val jobId: String,
    val text: String,
    val source: TranscriptionJob.Source,
)

@Singleton
class TranscriptionQueueService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisperService: WhisperService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    // Persistent storage directories
    private val queueDir = File(context.filesDir, "transcription_queue").also { it.mkdirs() }
    private val queueFile = File(queueDir, "queue.json")

    // Reactive state
    private val _jobs = MutableStateFlow<List<TranscriptionJob>>(emptyList())
    val jobs: StateFlow<List<TranscriptionJob>> = _jobs.asStateFlow()

    private val _successEvents = MutableSharedFlow<TranscriptionSuccessEvent>()
    val successEvents: SharedFlow<TranscriptionSuccessEvent> = _successEvents.asSharedFlow()

    // One-shot result for the immediate enqueue call
    private val _pendingResult = MutableStateFlow<PendingResult?>(null)
    val pendingResult: StateFlow<PendingResult?> = _pendingResult.asStateFlow()

    init {
        // Load persisted jobs on startup
        loadJobs()
        // Clean up any completed jobs from previous sessions
        cleanupCompleted()
    }

    sealed class PendingResult {
        data class Success(val text: String) : PendingResult()
        data class Failed(val jobId: String, val error: String) : PendingResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue an audio file for transcription with immediate attempt.
     * Moves the file to persistent storage first, then tries up to [maxRetries]
     * with exponential backoff.
     *
     * @return The transcription text on immediate success, or null if queued for retry.
     */
    fun enqueue(
        audioPath: String,
        apiKey: String,
        source: TranscriptionJob.Source,
        sourceSessionId: String? = null,
    ) {
        scope.launch {
            // Move audio to persistent storage
            val src = File(audioPath)
            if (!src.exists()) return@launch

            val persistentName = "${UUID.randomUUID()}.m4a"
            val dest = File(queueDir, persistentName)
            src.copyTo(dest, overwrite = true)
            src.delete()

            val job = TranscriptionJob(
                id = UUID.randomUUID().toString(),
                audioPath = dest.absolutePath,
                timestamp = System.currentTimeMillis(),
                source = source,
                sourceSessionId = sourceSessionId,
            )

            // Try immediate transcription with retries
            attemptWithRetries(job, apiKey)
        }
    }

    /**
     * Manually retry a failed transcription job.
     */
    fun retryJob(jobId: String, apiKey: String) {
        scope.launch {
            val job = _jobs.value.find { it.id == jobId } ?: return@launch
            val resetJob = job.copy(
                status = TranscriptionJob.Status.PENDING,
                retryCount = 0,
                error = null,
            )
            updateJob(resetJob)
            attemptWithRetries(resetJob, apiKey)
        }
    }

    /**
     * Dismiss a failed job and delete its audio file.
     */
    fun dismissJob(jobId: String) {
        scope.launch {
            val job = _jobs.value.find { it.id == jobId } ?: return@launch
            File(job.audioPath).delete()
            removeJob(jobId)
        }
    }

    /**
     * Clear the pending result after it's been consumed.
     */
    fun clearPendingResult() {
        _pendingResult.value = null
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun attemptWithRetries(job: TranscriptionJob, apiKey: String) {
        var currentJob = job

        for (attempt in 0 until currentJob.maxRetries) {
            currentJob = currentJob.copy(
                retryCount = attempt,
                status = if (attempt == 0) TranscriptionJob.Status.PENDING
                         else TranscriptionJob.Status.RETRYING,
            )
            updateJob(currentJob)

            val result = whisperService.transcribe(currentJob.audioPath, apiKey)

            when (result) {
                is TranscriptionResult.Success -> {
                    // Success — clean up
                    File(currentJob.audioPath).delete()
                    removeJob(currentJob.id)
                    val text = result.text

                    // Notify caller
                    if (attempt == 0) {
                        _pendingResult.value = PendingResult.Success(text)
                    }

                    // Notify any observers (e.g. for retries that succeed later)
                    _successEvents.emit(
                        TranscriptionSuccessEvent(currentJob.id, text, currentJob.source)
                    )
                    return
                }
                is TranscriptionResult.FatalError -> {
                    // Non-retryable — fail immediately
                    currentJob = currentJob.copy(
                        status = TranscriptionJob.Status.FAILED,
                        error = result.message,
                    )
                    updateJob(currentJob)
                    if (attempt == 0) {
                        _pendingResult.value = PendingResult.Failed(currentJob.id, result.message)
                    }
                    return
                }
                is TranscriptionResult.RetryableError -> {
                    currentJob = currentJob.copy(error = result.message)
                    // Wait with exponential backoff before next attempt
                    if (attempt < currentJob.maxRetries - 1) {
                        val backoffMs = INITIAL_BACKOFF_MS * (1L shl attempt)
                        delay(backoffMs)
                    }
                }
            }
        }

        // All retries exhausted
        currentJob = currentJob.copy(
            status = TranscriptionJob.Status.FAILED,
            error = currentJob.error ?: "Max retries (${currentJob.maxRetries}) exhausted",
        )
        updateJob(currentJob)
        if (job.retryCount == 0) {
            _pendingResult.value = PendingResult.Failed(currentJob.id, currentJob.error ?: "Transcription failed")
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Serializable
    private data class JobEntry(
        val id: String,
        val audioPath: String,
        val timestamp: Long,
        val source: String,
        val retryCount: Int,
        val maxRetries: Int,
        val status: String,
        val error: String? = null,
        val sourceSessionId: String? = null,
    )

    private suspend fun updateJob(job: TranscriptionJob) = mutex.withLock {
        val list = _jobs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == job.id }
        if (idx >= 0) list[idx] = job else list.add(job)
        _jobs.value = list
        persistJobs()
    }

    private suspend fun removeJob(jobId: String) = mutex.withLock {
        _jobs.value = _jobs.value.filter { it.id != jobId }
        persistJobs()
    }

    private fun loadJobs() {
        if (!queueFile.exists()) return
        try {
            val entries = json.decodeFromString<List<JobEntry>>(queueFile.readText())
            _jobs.value = entries.map { it.toModel() }
        } catch (_: Exception) { /* corrupted file, start fresh */ }
    }

    private fun persistJobs() {
        try {
            val entries = _jobs.value.map { it.toEntry() }
            queueFile.writeText(json.encodeToString(entries))
        } catch (_: Exception) { /* best effort */ }
    }

    private fun cleanupCompleted() {
        scope.launch {
            val completed = _jobs.value.filter { it.status == TranscriptionJob.Status.COMPLETED }
            completed.forEach { job ->
                File(job.audioPath).delete()
                removeJob(job.id)
            }
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun JobEntry.toModel() = TranscriptionJob(
        id = id,
        audioPath = audioPath,
        timestamp = timestamp,
        source = TranscriptionJob.Source.valueOf(source),
        retryCount = retryCount,
        maxRetries = maxRetries,
        status = TranscriptionJob.Status.valueOf(status),
        error = error,
        sourceSessionId = sourceSessionId,
    )

    private fun TranscriptionJob.toEntry() = JobEntry(
        id = id,
        audioPath = audioPath,
        timestamp = timestamp,
        source = source.name,
        retryCount = retryCount,
        maxRetries = maxRetries,
        status = status.name,
        error = error,
        sourceSessionId = sourceSessionId,
    )

    fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2000L // 2s, 4s, 8s
    }
}
