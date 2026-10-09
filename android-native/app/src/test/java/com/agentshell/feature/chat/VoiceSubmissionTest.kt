package com.agentshell.feature.chat

import com.agentshell.data.services.TranscriptionReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceSubmissionTest {
    @Test fun clearingTheUiEventDoesNotCancelPendingAutomaticSubmission() = runTest {
        val enabled = CompletableDeferred<Boolean>()
        val owner = CoroutineScope(coroutineContext + Job())
        val ui = CoroutineScope(coroutineContext + Job())
        val sent = mutableListOf<String>()
        ui.launch { owner.submitVoiceDraft("dictated", { enabled.await() }, { "dictated" }, sent::add) }
        runCurrent()
        // Recomposition cancels the effect when its transcription key is cleared.
        ui.cancel()
        enabled.complete(true)
        runCurrent()
        assertEquals(listOf("dictated"), sent)
        owner.cancel()
    }

    @Test fun aManualSendWhilePreferencesLoadPreventsASecondAutomaticSend() = runTest {
        val enabled = CompletableDeferred<Boolean>()
        var draft = "dictated"
        val sent = mutableListOf<String>()
        submitVoiceDraft(draft, { enabled.await() }, { draft }, sent::add)
        runCurrent()
        sent.add(draft)
        draft = ""
        enabled.complete(true)
        runCurrent()
        assertEquals(listOf("dictated"), sent)
    }

    @Test fun disabledAutomaticSendAndUserEditsPreserveTheDraft() = runTest {
        val sent = mutableListOf<String>()
        submitVoiceDraft("dictated", { false }, { "dictated" }, sent::add)
        submitVoiceDraft("dictated", { true }, { "edited dictated text" }, sent::add)
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun immediateResultAndRetryEventForOneJobAreHandledOnce() {
        val receipts = TranscriptionReceipt()
        assertTrue(receipts.accept("recording-one"))
        assertFalse(receipts.accept("recording-one"))
        // Another recording is accepted even if it produces exactly the same words.
        assertTrue(receipts.accept("recording-two"))
    }
}
