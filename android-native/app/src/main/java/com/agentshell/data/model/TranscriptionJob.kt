package com.agentshell.data.model

/**
 * Represents a pending or completed audio transcription job.
 * Jobs are persisted to survive app restarts and network outages.
 */
data class TranscriptionJob(
    val id: String,
    val audioPath: String,
    val timestamp: Long,
    val source: Source,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val status: Status = Status.PENDING,
    val error: String? = null,
    val sourceSessionId: String? = null,
) {
    enum class Source { TERMINAL, CHAT }
    enum class Status { PENDING, RETRYING, FAILED, COMPLETED }

    val canRetry: Boolean get() = retryCount < maxRetries && status != Status.COMPLETED
}
