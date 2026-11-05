package com.agentshell.feature.chat

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatBindingState
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.ConversationBinding
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.*
import com.agentshell.data.services.*
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic protocol frames exercise the real collector; no server or agent is contacted. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ChatSubmissionViewModelTest {
    @get:Rule val directory=TemporaryFolder()
    private lateinit var app:Application
    private lateinit var preferences:PreferencesDataStore
    private lateinit var submissions:ChatSubmissionStore
    private lateinit var database:AppDatabase
    private lateinit var socket:WebSocketService
    private lateinit var hosts:HostRepository
    private lateinit var cache:OfflineCacheRepository
    private lateinit var attachments:OfflineAttachmentStore
    private lateinit var activities:ChatActivityRepository
    private lateinit var audio:AudioService
    private lateinit var transcription:TranscriptionQueueService
    private lateinit var recent:RecentSessionsRepository
    private val client=OkHttpClient()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    // These tests deliberately use real Room/DataStore I/O and real deadlines.
    // A virtual UnconfinedTestDispatcher mixed with runBlocking leaves combine
    // continuations waiting on a scheduler which this fixture never advances.
    private val main=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models=mutableListOf<ViewModelStore>()
    private val host=Host("fixture","Synthetic chat","127.0.0.1",40199)
    private val ticket=ChatSubmission("request-1","ws://127.0.0.1:40199/ws","native:fixture","binding-1","fixture",0,"Original message","chat_draft_native_ws://127.0.0.1:40199/ws:native:fixture")
    private val binding=ChatBindingState("bound","pane","claude",ConversationBinding("binding-1","conversation","native:fixture","hook"),emptyList(),"Linked")

    @Before fun setup()=runBlocking<Unit> {
        Dispatchers.setMain(main)
        app=ApplicationProvider.getApplicationContext()
        preferences=PreferencesDataStore(PreferenceDataStoreFactory.create(scope=scope,
            produceFile={File(directory.root,"chat.preferences_pb")}))
        preferences.setSelectedHostId(host.id)
        val dao=object:HostDao {
            override fun getAll()=flowOf(listOf(host))
            override suspend fun getById(id:String)=host.takeIf { it.id==id }
            override suspend fun insert(host:Host)=Unit
            override suspend fun delete(host:Host)=Unit
            override suspend fun deleteById(id:String)=Unit
        }
        hosts=HostRepository(dao,preferences);socket=WebSocketService(client)
        submissions=ChatSubmissionStore(preferences)
        database=Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).build()
        attachments=OfflineAttachmentStore(app,client)
        cache=OfflineCacheRepository(database,hosts,socket,client,attachments)
        activities=ChatActivityRepository(app,preferences,hosts,socket,client)
        audio=AudioService(app);transcription=TranscriptionQueueService(app,WhisperService())
        recent=RecentSessionsRepository(preferences,flowOf(host),socket.connectionStatus,socket.messages,
            {null},{},scope)
    }

    @After fun cleanup()=runBlocking<Unit> {
        withContext(main) { models.forEach { it.clear() } }
        cache.close();attachments.close();activities.close();socket.dispose()
        audio.dispose();transcription.dispose();scope.coroutineContext[Job]?.cancelAndJoin();database.close()
        withContext(main) { } // Drain cancellation callbacks before closing the executor.
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();Dispatchers.resetMain();main.close()
    }

    private fun field(model:ChatViewModel,name:String,value:Any?) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.set(model,value)
    }
    @Suppress("UNCHECKED_CAST")
    private fun state(model:ChatViewModel)=ChatViewModel::class.java.getDeclaredField("_uiState").run {
        isAccessible=true;get(model) as MutableStateFlow<ChatUiState>
    }
    private suspend fun model(draft:String=ticket.text,file:AttachedFile?=null,restoring:Boolean=false):ChatViewModel = withContext(main) {
        val model=ChatViewModel(recent,SavedStateHandle(),ChatRepository(socket),cache,activities,hosts,
            SharedContentRepository(app),preferences,submissions,audio,transcription,app,SystemRepository(socket),AudioPlayerManager(app,preferences))
        models.add(ViewModelStore().apply { put("chat",model) })
        field(model,"readHostServerUrl",ticket.serverUrl);field(model,"restoringCache",restoring)
        state(model).value=ChatUiState(sessionName=ticket.sessionName,windowIndex=0,bindingState=binding,
            draftMessage=draft,attachedFile=file,isLoading=restoring)
        model.setScreenActive(true)
        model
    }
    @Suppress("UNCHECKED_CAST")
    private suspend fun receipt(server:String=ticket.serverUrl) {
        val messages=WebSocketService::class.java.getDeclaredField("_messages").run { isAccessible=true;get(socket) as MutableSharedFlow<Map<String,Any?>> }
        // Constructor jobs subscribe on this same executor. A queue barrier
        // ensures the real chat collector is ready, rather than counting other
        // repositories which also subscribe to this shared socket.
        withContext(main) { }
        messages.emit(mapOf("type" to "chat-send-result","_sourceServerUrl" to server,
            "requestId" to ticket.id,"bindingId" to ticket.bindingId,"status" to "complete","success" to true))
    }
    private suspend fun await(condition:suspend ()->Boolean)=withTimeout(10_000) { while(!condition())delay(10) }

    private suspend fun reopenChat():ChatViewModel {
        val model=model(draft="",restoring=true)
        withContext(main) { model.watchChatLog(ticket.sessionName,ticket.windowIndex) }
        await { model.uiState.value.readTrackingReady && !model.uiState.value.isLoading }
        return model
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun bindChat(model:ChatViewModel,sessionName:String=ticket.sessionName,conversationKey:String=ticket.conversationKey) {
        val messages=WebSocketService::class.java.getDeclaredField("_messages").run {
            isAccessible=true;get(socket) as MutableSharedFlow<Map<String,Any?>>
        }
        messages.emit(mapOf("type" to "chat-binding","_sourceServerUrl" to ticket.serverUrl,
            "sessionName" to sessionName,"windowIndex" to ticket.windowIndex,
            "state" to mapOf("status" to "bound","tool" to "claude","binding" to mapOf(
                "bindingId" to ticket.bindingId,"conversationId" to "conversation",
                "conversationKey" to conversationKey,"source" to "hook"))))
        await { model.uiState.value.bindingState?.let { it.canSend && it.binding?.conversationKey==conversationKey } == true }
    }

    private suspend fun expectDraft(model:ChatViewModel,expected:String) {
        val matched=withTimeoutOrNull(2_000) {
            while(model.uiState.value.draftMessage!=expected)delay(10)
            true
        }
        assertEquals("Expected draft <$expected>, got <${model.uiState.value.draftMessage}>",true,matched)
    }

    @Test fun returningToACachedConversationRestoresItsUnsentDraftInsteadOfAnOldSessionDraft()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A very old sent prompt")
        preferences.setDraftMessage(ticket.draftKey,"My next unsent message")
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,"My next unsent message")
    }

    @Test fun returningAfterSuccessfulDeliveryDoesNotResurrectAnOlderSessionPrompt()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A very old sent prompt")
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        receipt();await { first.uiState.value.draftMessage.isEmpty() }
        await { preferences.getDraftMessage(ticket.draftKey)==null }
        withContext(main) { models.first().clear() }
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,"")
    }

    @Test fun firstConversationBindingKeepsAnUnsentSessionDraftButRetiresItsOldKey()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A message I have not sent")
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,"A message I have not sent")
        await { preferences.getDraftMessage(ticket.draftKey)=="A message I have not sent" }
        assertNull("The session fallback must not resurrect this draft after a later send",
            preferences.getDraftMessage("chat_draft_fixture_0"))
    }

    @Test fun anOfflineCachedChatRestoresItsUnsentNativeDraftWithoutWaitingForALiveBinding()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A very old sent prompt")
        preferences.setDraftMessage(ticket.draftKey,"Unsent while offline")
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat()
        expectDraft(reopened,"Unsent while offline")
        assertFalse(socket.isConnected)
        assertFalse(reopened.uiState.value.bindingState?.canSend==true)
    }

    @Test fun anExplicitlyEmptyNativeDraftWinsOverAnOldSessionPrompt()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A very old sent prompt")
        preferences.setDraftMessage(ticket.draftKey,"",keepEmpty=true)
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat();bindChat(reopened)
        withContext(main) { }
        expectDraft(reopened,"")
        assertEquals("",preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun typingDuringInitialRestorationSurvivesBindingAndAnotherVisit()=runBlocking<Unit> {
        preferences.setDraftMessage("chat_draft_fixture_0","A very old sent prompt")
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val first=model(draft="")
        withContext(main) {
            first.watchChatLog(ticket.sessionName,0)
            first.updateDraft("Typing my next message")
        }
        await { first.uiState.value.readTrackingReady && !first.uiState.value.isLoading }
        bindChat(first)
        await { preferences.getDraftMessage(ticket.draftKey)=="Typing my next message" }
        expectDraft(first,"Typing my next message")
        withContext(main) { models.first().clear() }
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,"Typing my next message")
    }

    @Test fun leavingImmediatelyAfterEditingStillPersistsTheUnsentDraft()=runBlocking<Unit> {
        val first=model()
        withContext(main) { first.updateDraft("Keep this unsent message");models.first().clear() }
        await { preferences.getDraftMessage(ticket.draftKey)=="Keep this unsent message" }
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,"Keep this unsent message")
    }

    @Test fun watchingAnotherChatDoesNotCarryThePreviousComposersDraftIntoIt()=runBlocking<Unit> {
        val model=model()
        withContext(main) {
            model.updateDraft("Unsent in the first chat")
            model.watchChatLog("other-chat",0)
        }
        await { model.uiState.value.readTrackingReady && !model.uiState.value.isLoading }
        bindChat(model,sessionName="other-chat",conversationKey="native:other")
        expectDraft(model,"")
        await { preferences.getDraftMessage(ticket.draftKey)=="Unsent in the first chat" }
        assertNull(preferences.getDraftMessage("chat_draft_native_${ticket.serverUrl}:native:other"))
    }

    @Test fun changingNativeConversationsKeepsBothDraftsAndRepeatedBindingsKeepNewTyping()=runBlocking<Unit> {
        val nextKey="chat_draft_native_${ticket.serverUrl}:native:other"
        preferences.setDraftMessage(nextKey,"Other conversation's unsent message")
        val model=model()
        withContext(main) { model.updateDraft("First conversation's unsent message") }
        bindChat(model,conversationKey="native:other")
        expectDraft(model,"Other conversation's unsent message")
        await { preferences.getDraftMessage(ticket.draftKey)=="First conversation's unsent message" }
        withContext(main) { model.updateDraft("New typing in the other conversation") }
        bindChat(model,conversationKey="native:other")
        await { preferences.getDraftMessage(nextKey)=="New typing in the other conversation" }
        expectDraft(model,"New typing in the other conversation")
    }

    @Test fun acknowledgementDuringCacheRestorationIsRetainedAndSettledAfterTheCacheFinishes()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val model=model(restoring=true);await { model.uiState.value.isSending }
        receipt()
        // Observe committed snapshots through the same bounded helper used by
        // the fixture's other real Room/DataStore I/O assertions.
        await { submissions.records.first().any { it.id==ticket.id && it.phase=="complete" && it.success==true } }
        assertEquals(ticket.text,model.uiState.value.draftMessage)
        withContext(main) { field(model,"restoringCache",false);state(model).value=state(model).value.copy(isLoading=false) }
        await { model.uiState.value.draftMessage.isEmpty() }
        await { preferences.getDraftMessage(ticket.draftKey) == null }
        assertNull(preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun aRecreatedChatSettlesItsPersistedUnknownTicketWithoutSendingAnotherMessage()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending };withContext(main) { models.first().clear() }
        submissions.checking(ticket.id,receiptsAvailable=false)
        val restored=model(draft="");await { restored.uiState.value.draftMessage==ticket.text }
        receipt();await { restored.uiState.value.draftMessage.isEmpty() }
        assertFalse(restored.uiState.value.isSending)
        await { preferences.getDraftMessage(ticket.draftKey) == null }
    }

    @Test fun changedAttachmentWithTheSameCaptionKeepsBothTheLiveAndSavedNewDraft()=runBlocking<Unit> {
        val original=AttachedFile("content://test/one","image.png","image/png",10)
        val replacement=original.copy(uri="content://test/two")
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket.copy(file=original))
        val model=model(file=replacement);await { model.uiState.value.isSending }
        receipt();await { !model.uiState.value.isSending }
        assertEquals(ticket.text,model.uiState.value.draftMessage);assertEquals(replacement,model.uiState.value.attachedFile)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun anOldConversationReceiptCannotClearTheSameTextInANewConversation()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val model=model();await { model.uiState.value.isSending }
        withContext(main) { state(model).value=state(model).value.copy(bindingState=binding.copy(binding=binding.binding!!.copy(id="new-binding",conversationKey="new-conversation"))) }
        await { !model.uiState.value.isSending };receipt()
        await { submissions.records.first().any { it.id==ticket.id && it.phase=="complete" && it.success==true } }
        assertEquals(ticket.text,model.uiState.value.draftMessage)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun deliberatelyClearingThePendingDraftSurvivesAnotherReceiptAndViewRecreation()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        withContext(main) { first.updateDraft("") }
        await { submissions.records.first().any { it.id==ticket.id && it.draftEdited } }
        submissions.checking(ticket.id)
        assertEquals("",first.uiState.value.draftMessage)
        withContext(main) { models.first().clear() }
        val restored=model(draft="")
        await { restored.uiState.value.isSending }
        receipt();await { !restored.uiState.value.isSending }
        assertEquals("",restored.uiState.value.draftMessage)
        assertEquals("",preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun anotherViewCannotConsumeTheReceiptWhileTheVisibleChatRestoresItsCache()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val previous=model(draft="A newer draft in another view")
        val visible=model(restoring=true)
        await { previous.uiState.value.isSending && visible.uiState.value.isSending }
        receipt();await { !previous.uiState.value.isSending }
        delay(100) // Let the other view finish processing its acknowledgement.
        withContext(main) { field(visible,"restoringCache",false);state(visible).value=state(visible).value.copy(isLoading=false) }
        val cleared=withTimeoutOrNull(2_000) { while(visible.uiState.value.draftMessage.isNotEmpty())delay(10);true }
        assertEquals("The completed receipt must survive until the visible view can settle it",true,cleared)
        assertEquals("A newer draft in another view",previous.uiState.value.draftMessage)
    }

    @Test fun pastingTheSamePromptAfterSuccessIsANewDraftAndSurvivesRecreation()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        receipt();await { first.uiState.value.draftMessage.isEmpty() }
        withTimeout(10_000) { while(preferences.getDraftMessage(ticket.draftKey)!=null)delay(10) }
        withContext(main) { first.updateDraft(ticket.text) }
        await { submissions.records.first().any { it.id==ticket.id && it.draftEdited } }
        withContext(main) { models.first().clear() }
        val restored=model(draft=ticket.text)
        delay(100)
        assertEquals(ticket.text,restored.uiState.value.draftMessage)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun pastingTheSamePromptAndImmediatelyLeavingKeepsItAsANewUnsentDraft()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        receipt();await { first.uiState.value.draftMessage.isEmpty() }
        await { preferences.getDraftMessage(ticket.draftKey)==null }
        withContext(main) { first.updateDraft(ticket.text);models.first().clear() }
        cache.saveHistory(ChatTarget.create(host.id,ticket.sessionName,0,false),emptyList(),true,ticket.conversationKey)
        val reopened=reopenChat();bindChat(reopened)
        expectDraft(reopened,ticket.text)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun aSuccessfulReceiptDoesNotRestoreTheSentTextInARecreatedEmptyComposer()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        receipt();await { first.uiState.value.draftMessage.isEmpty() }
        withTimeout(10_000) { while(preferences.getDraftMessage(ticket.draftKey)!=null)delay(10) }
        withContext(main) { models.first().clear() }
        val restored=model(draft="")
        delay(100)
        assertEquals("",restored.uiState.value.draftMessage)
        assertFalse(restored.uiState.value.isSending)
    }
}
