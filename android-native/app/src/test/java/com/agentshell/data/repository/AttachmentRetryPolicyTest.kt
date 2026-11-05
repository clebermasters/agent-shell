package com.agentshell.data.repository

import com.agentshell.core.power.BackgroundWorkState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentRetryPolicyTest {
    private val policy = AttachmentRetryPolicy(jitter = { 0L })
    private fun job(attempts: Int = 1) = AttachmentDownloadJob(url = "https://server/files/id", attempts = attempts)

    @Test fun transientErrorsBackOffExponentiallyAndRemainBounded() = runTest {
        assertEquals(60_000, policy.failed(job(), 503, null, testScheduler.currentTime).nextAttemptAtMillis)
        testScheduler.advanceTimeBy(10_000)
        assertEquals(130_000, policy.failed(job(2), null, null, testScheduler.currentTime).nextAttemptAtMillis)
        assertEquals(6 * 60 * 60_000L, policy.retryDelay(100))
        val largestJitter = AttachmentRetryPolicy(jitter = { it })
        assertEquals(75_000, largestJitter.retryDelay(1))
    }

    @Test fun unavailableAndAuthOutcomesPreserveJobsWithLongerEligibilityDelays() {
        for (status in listOf(404, 410)) {
            val failed = policy.failed(job(), status, null, 1_000)
            assertEquals(AttachmentOutcome.UNAVAILABLE, failed.outcome)
            assertEquals(24 * 60 * 60_000L + 1_000, failed.nextAttemptAtMillis)
            assertEquals(job().url, failed.url)
        }
        assertEquals(AttachmentOutcome.AUTH_REQUIRED, policy.failed(job(), 401, null, 0).outcome)
        assertEquals(AttachmentOutcome.AUTH_REQUIRED, policy.failed(job(), 403, null, 0).outcome)
    }

    @Test fun rateLimitsRespectBothRetryAfterFormsAndRejectInvalidDelays() {
        val now = 1_700_000_000_000L
        assertEquals(now + 600_000, policy.failed(job(), 429, "600", now).nextAttemptAtMillis)
        assertEquals(now + 60_000, policy.failed(job(), 429, "-100", now).nextAttemptAtMillis)
        assertEquals(now + 60_000, policy.failed(job(), 503, "nonsense", now).nextAttemptAtMillis)
        val date = "Wed, 15 Nov 2023 00:00:00 GMT"
        assertEquals(1_700_006_400_000L, policy.retryAfterMillis(date, now))
        assertEquals(now + 24 * 60 * 60_000L, policy.retryAfterMillis("999999999", now))
    }

    @Test fun backgroundMeteredWorkHasAFiniteAutomaticBudget() {
        val metered = policy.budget(BackgroundWorkState(metered = true, foreground = false, interactive = false))
        val wifi = policy.budget(BackgroundWorkState())
        val foreground = policy.budget(BackgroundWorkState(foreground = true))
        assertTrue(metered.attempts > 0)
        assertTrue(metered.attempts < wifi.attempts)
        assertTrue(metered.windowMillis > wifi.windowMillis)
        assertTrue(foreground.attempts > wifi.attempts)
    }
}
