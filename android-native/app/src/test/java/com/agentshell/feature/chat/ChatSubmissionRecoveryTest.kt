package com.agentshell.feature.chat

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.agentshell.data.local.PreferencesDataStore
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ChatSubmissionRecoveryTest {
    @get:Rule val directory=TemporaryFolder()
    private val ticket=ChatSubmission("request-1","wss://main.test/ws","conversation-1","binding-1","chat",0,"original draft","draft-1")
    private fun receipt(status: String?="complete", success: Boolean?=true, binding: String=ticket.bindingId) = buildMap<String,Any?> {
        put("requestId",ticket.id);put("bindingId",binding)
        if(status!=null)put("status",status)
        if(success!=null)put("success",success)
    }

    @Test fun lateSuccessStillSettlesAfterTheOriginalTimeoutAndDisconnect() {
        val checking=ticket.copy(phase="checking")
        val settled=checking.receipt(ticket.serverUrl,receipt())
        assertEquals("complete",settled.phase);assertEquals(true,settled.success);assertFalse(settled.waiting)
        assertEquals(ticket.id,settled.id)
    }

    @Test fun receiptsCannotCrossServersOrConversationBindings() {
        assertEquals(ticket,ticket.receipt("wss://homelab.test/ws",receipt()))
        assertEquals(ticket,ticket.receipt(ticket.serverUrl,receipt(binding="another-binding")))
        assertEquals(ticket,ticket.receipt(ticket.serverUrl,receipt()+mapOf("requestId" to "another-request")))
    }

    @Test fun pendingAndUnknownAreNotSuccessfulAndNeverContainAnInputReplay() {
        val pending=ticket.receipt(ticket.serverUrl,receipt("pending",null))
        assertTrue(pending.waiting);assertNull(pending.success)
        assertEquals(mapOf("type" to "get-chat-send-result","requestId" to ticket.id,"bindingId" to ticket.bindingId),pending.lookup)
        val unknown=pending.receipt(ticket.serverUrl,receipt("unknown",null))
        assertFalse(unknown.waiting);assertEquals(ticket.text,unknown.text);assertNull(unknown.success)
        assertEquals("complete",unknown.receipt(ticket.serverUrl,receipt()).phase)
    }

    @Test fun anEditedAttachmentKeepsTheEntireNewDraftEvenIfItsCaptionIsUnchanged() {
        val original=AttachedFile("content://test/original","photo.jpg","image/jpeg",1234)
        val next=original.copy(uri="content://test/next")
        val sent=ticket.copy(file=original)
        assertTrue(sent.matchesDraft(ticket.text,original))
        assertFalse(sent.matchesDraft(ticket.text,next))
        assertFalse(sent.matchesDraft("my next message",original))
        assertFalse(sent.matchesDraft(ticket.text,null))
    }

    @Test fun olderBackendFinalAcknowledgementsRemainCompatibleAndDuplicateResultsCannotUndoSuccess() {
        val accepted=ticket.receipt(ticket.serverUrl,receipt(null))
        assertEquals(true,accepted.success)
        assertEquals(accepted,accepted.receipt(ticket.serverUrl,receipt("pending",null)))
        assertEquals(accepted,accepted.receipt(ticket.serverUrl,receipt(success=false)))
    }

    private fun withStore(block:suspend (PreferencesDataStore,ChatSubmissionStore)->Unit)=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try {
            val preferences=PreferencesDataStore(PreferenceDataStoreFactory.create(scope=scope,
                produceFile={File(directory.root,"submissions.preferences_pb")}))
            block(preferences,ChatSubmissionStore(preferences))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin();dispatcher.close() }
    }

    @Test fun recreatedChatRestoresTheOriginalTicketAndCanQueryWithoutResending()=withStore { preferences,store ->
        assertTrue(store.begin(ticket));store.checking(ticket.id)
        val reopened=ChatSubmissionStore(preferences)
        val restored=reopened.records.first().single()
        assertEquals(ticket.id,restored.id);assertEquals("checking",restored.phase)
        assertEquals(ticket.lookup,restored.lookup)
        reopened.record(ticket.serverUrl,receipt())
        assertEquals("complete",store.records.first().single().phase)
    }

    @Test fun aSecondTapIsBlockedUntilResolutionAndExplicitRetryAfterUnknownUsesANewTicket()=withStore { _,store ->
        assertTrue(store.begin(ticket));assertFalse(store.begin(ticket.copy(id="request-2")))
        store.record(ticket.serverUrl,receipt("unknown",null))
        assertTrue(store.begin(ticket.copy(id="request-2")))
        assertEquals("request-2",store.records.first().single().id)
    }

    @Test fun successfulDeliveryOnlyClearsTheMatchingSavedDraft()=withStore { preferences,store ->
        preferences.setDraftMessage(ticket.draftKey,ticket.text)
        store.clearSavedDraft(ticket);assertNull(preferences.getDraftMessage(ticket.draftKey))
        preferences.setDraftMessage(ticket.draftKey,"my next message")
        store.clearSavedDraft(ticket)
        assertEquals("my next message",preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun attachmentMetadataAndWidgetTicketsSurviveRecreationWithoutFileBytes()=withStore { preferences,store ->
        val file=AttachedFile("content://test/document/1","photo.jpg","image/jpeg",1234)
        assertTrue(store.begin(ticket.copy(file=file)))
        assertEquals(file,ChatSubmissionStore(preferences).records.first().single().file)
        store.remove(ticket.id)
        preferences.setDraftMessage(ticket.draftKey,ticket.text)
        assertTrue(store.begin(ticket.copy(widgetId="widget-1")))
        store.clearSavedDraft(ticket.copy(widgetId="widget-1"))
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun rejectionPreservesTheDraftAndTimeoutCannotDowngradeACompletedReceipt()=withStore { preferences,store ->
        preferences.setDraftMessage(ticket.draftKey,ticket.text);store.begin(ticket)
        store.record(ticket.serverUrl,receipt(success=false)+mapOf("error" to "Conversation changed"))
        store.checking(ticket.id)
        val result=store.records.first().single()
        assertFalse(result.waiting);assertEquals(false,result.success);assertEquals("Conversation changed",result.error)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun recreatedChatCanQueryATicketMarkedUnknownBeforeCapabilitiesArrive()=withStore { preferences,store ->
        store.begin(ticket);store.checking(ticket.id,receiptsAvailable=false)
        val restored=ChatSubmissionStore(preferences).records.first().single()
        assertTrue(restored.unresolved);assertFalse(restored.waiting)
        assertEquals("get-chat-send-result",restored.lookup["type"])
        store.record(restored.serverUrl,receipt())
        assertEquals(true,store.records.first().single().success)
    }

    @Test fun exhaustedLookupBudgetReleasesTheSendSpinnerButRetainsLateAcknowledgements()=withStore { _,store ->
        store.begin(ticket);store.record(ticket.serverUrl,receipt("pending",null));store.unconfirmed(ticket.id)
        val unknown=store.records.first().single()
        assertFalse(unknown.waiting);assertTrue(unknown.unresolved);assertEquals(ticket.text,unknown.text)
        store.record(ticket.serverUrl,receipt())
        assertEquals("complete",store.records.first().single().phase)
    }

    @Test fun oldFinalTicketsCannotPermanentlyFillTheStore()=withStore { _,store ->
        for(i in 0 until 70) {
            val item=ticket.copy(id="request-$i",conversationKey="conversation-$i",bindingId="binding-$i")
            assertTrue(store.begin(item));store.unconfirmed(item.id)
        }
        assertEquals(64,store.records.first().size)
        assertTrue(store.begin(ticket.copy(id="fresh",conversationKey="new-conversation")))
        assertEquals(64,store.records.first().size)
    }
}
