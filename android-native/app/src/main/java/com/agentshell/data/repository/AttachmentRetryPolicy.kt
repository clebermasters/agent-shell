package com.agentshell.data.repository

import com.agentshell.core.power.BackgroundWorkState
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

@Serializable
internal data class AttachmentDownloadJob(
    val version: Int = 1,
    val url: String,
    val sourceRevision: String = "",
    val sourceSizeBytes: Long? = null,
    val sourceMimeType: String? = null,
    val credentialRevision: String = "",
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val lastAttemptAtMillis: Long = 0,
    val outcome: AttachmentOutcome = AttachmentOutcome.PENDING,
    val manual: Boolean = false,
)

@Serializable
internal enum class AttachmentOutcome { PENDING, RETRY, UNAVAILABLE, AUTH_REQUIRED, RATE_LIMITED, IN_FLIGHT }

@Serializable
internal data class AttachmentHostThrottle(
    val nextAttemptAtMillis: Long,
    val outcome: AttachmentOutcome,
    val credentialRevision: String = "",
)

@Serializable
internal data class AttachmentQueueState(
    val windowEndsAtMillis: Long = 0,
    val windowStartedAtMillis: Long = 0,
    val attemptsInWindow: Int = 0,
    val bytesInWindow: Long = 0,
    val hosts: Map<String, AttachmentHostThrottle> = emptyMap(),
)

internal data class AttachmentBudget(val attempts: Int, val bytes: Long, val windowMillis: Long)

/** Retry deadlines are durable; this policy never deletes an unavailable attachment. */
internal class AttachmentRetryPolicy(
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound + 1) },
) {
    fun budget(state: BackgroundWorkState): AttachmentBudget = when {
        state.foreground && state.interactive -> AttachmentBudget(24, 8L * 1024 * 1024, 15_000)
        state.metered -> AttachmentBudget(4, 256L * 1024, 300_000)
        state.charging -> AttachmentBudget(16, 4L * 1024 * 1024, 60_000)
        else -> AttachmentBudget(8, 1024L * 1024, 60_000)
    }

    fun retryDelay(attempts: Int): Long {
        val exponent = (attempts.coerceAtLeast(1) - 1).coerceAtMost(9)
        val base = (60_000L * (1L shl exponent)).coerceAtMost(MAX_TRANSIENT_DELAY)
        return (base + jitter(base / 4).coerceIn(0, base / 4)).coerceAtMost(MAX_TRANSIENT_DELAY)
    }

    fun failed(job: AttachmentDownloadJob, status: Int?, retryAfter: String?, now: Long): AttachmentDownloadJob {
        val outcome = when (status) {
            401, 403 -> AttachmentOutcome.AUTH_REQUIRED
            404, 410 -> AttachmentOutcome.UNAVAILABLE
            429 -> AttachmentOutcome.RATE_LIMITED
            in 400..499 -> AttachmentOutcome.UNAVAILABLE
            else -> AttachmentOutcome.RETRY
        }
        val delay = when (outcome) {
            AttachmentOutcome.UNAVAILABLE -> 24 * 60 * 60_000L
            AttachmentOutcome.AUTH_REQUIRED -> 6 * 60 * 60_000L
            else -> retryDelay(job.attempts)
        }
        val serverDue = if (status == 429 || status == 503) retryAfterMillis(retryAfter, now) else null
        return job.copy(nextAttemptAtMillis = maxOf(now + delay, serverDue ?: 0), outcome = outcome, manual = false)
    }

    fun retryAfterMillis(value: String?, now: Long): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val due = raw.toLongOrNull()?.takeIf { it >= 0 }?.let {
            now + it.coerceAtMost(MAX_SERVER_DELAY / 1000) * 1000
        } ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        return due?.takeIf { it > now }?.coerceAtMost(now + MAX_SERVER_DELAY)
    }

    companion object {
        private const val MAX_TRANSIENT_DELAY = 6 * 60 * 60_000L
        private const val MAX_SERVER_DELAY = 24 * 60 * 60_000L
    }
}
