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
import com.agentshell.data.model.ConversationBinding
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.*
import com.agentshell.data.services.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val models=mutableListOf<ViewModelStore>()
    private val host=Host("fixture","Synthetic chat","127.0.0.1",40199)
    private val ticket=ChatSubmission("request-1","ws://127.0.0.1:40199/ws","native:fixture","binding-1","fixture",0,"Original message","chat_draft_native_ws://127.0.0.1:40199/ws:native:fixture")
    private val binding=ChatBindingState("bound","pane","claude",ConversationBinding("binding-1","conversation","native:fixture","hook"),emptyList(),"Linked")

    @Before fun setup()=runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        models.forEach { it.clear() };cache.close();attachments.close();activities.close();socket.dispose()
        audio.dispose();transcription.dispose();scope.coroutineContext[Job]?.cancelAndJoin();database.close()
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();Dispatchers.resetMain()
    }

    private fun field(model:ChatViewModel,name:String,value:Any?) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.set(model,value)
    }
    @Suppress("UNCHECKED_CAST")
    private fun state(model:ChatViewModel)=ChatViewModel::class.java.getDeclaredField("_uiState").run {
        isAccessible=true;get(model) as MutableStateFlow<ChatUiState>
    }
    private fun model(draft:String=ticket.text,file:AttachedFile?=null,restoring:Boolean=false):ChatViewModel {
        val model=ChatViewModel(recent,SavedStateHandle(),ChatRepository(socket),cache,activities,hosts,
            SharedContentRepository(app),preferences,submissions,audio,transcription,app,SystemRepository(socket),AudioPlayerManager(app,preferences))
        models.add(ViewModelStore().apply { put("chat",model) })
        field(model,"readHostServerUrl",ticket.serverUrl);field(model,"restoringCache",restoring)
        state(model).value=ChatUiState(sessionName=ticket.sessionName,windowIndex=0,bindingState=binding,
            draftMessage=draft,attachedFile=file,isLoading=restoring)
        model.setScreenActive(true)
        return model
    }
    @Suppress("UNCHECKED_CAST")
    private suspend fun receipt(server:String=ticket.serverUrl) {
        val messages=WebSocketService::class.java.getDeclaredField("_messages").run { isAccessible=true;get(socket) as MutableSharedFlow<Map<String,Any?>> }
        withTimeout(10_000) { messages.subscriptionCount.first { it >= 3 } }
        messages.emit(mapOf("type" to "chat-send-result","_sourceServerUrl" to server,
            "requestId" to ticket.id,"bindingId" to ticket.bindingId,"status" to "complete","success" to true))
    }
    private suspend fun await(condition:()->Boolean)=withTimeout(10_000) { while(!condition())delay(10) }

    @Test fun acknowledgementDuringCacheRestorationIsRetainedAndSettledAfterTheCacheFinishes()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val model=model(restoring=true);await { model.uiState.value.isSending }
        receipt();withTimeout(10_000) { submissions.records.first { it.single().phase=="complete" } }
        assertEquals(ticket.text,model.uiState.value.draftMessage)
        field(model,"restoringCache",false);state(model).value=state(model).value.copy(isLoading=false)
        await { model.uiState.value.draftMessage.isEmpty() }
        withTimeout(10_000) { submissions.records.first { it.isEmpty() } }
        assertNull(preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun aRecreatedChatSettlesItsPersistedUnknownTicketWithoutSendingAnotherMessage()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending };models.first().clear()
        submissions.checking(ticket.id,receiptsAvailable=false)
        val restored=model(draft="");await { restored.uiState.value.draftMessage==ticket.text }
        receipt();await { restored.uiState.value.draftMessage.isEmpty() }
        assertFalse(restored.uiState.value.isSending)
        withTimeout(10_000) { submissions.records.first { it.isEmpty() } }
    }

    @Test fun changedAttachmentWithTheSameCaptionKeepsBothTheLiveAndSavedNewDraft()=runBlocking<Unit> {
        val original=AttachedFile("content://test/one","image.png","image/png",10)
        val replacement=original.copy(uri="content://test/two")
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket.copy(file=original))
        val model=model(file=replacement);await { model.uiState.value.isSending }
        receipt();withTimeout(10_000) { submissions.records.first { it.isEmpty() } }
        assertEquals(ticket.text,model.uiState.value.draftMessage);assertEquals(replacement,model.uiState.value.attachedFile)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun anOldConversationReceiptCannotClearTheSameTextInANewConversation()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val model=model();await { model.uiState.value.isSending }
        state(model).value=state(model).value.copy(bindingState=binding.copy(binding=binding.binding!!.copy(id="new-binding",conversationKey="new-conversation")))
        await { !model.uiState.value.isSending };receipt()
        val settled=withTimeoutOrNull(10_000) { submissions.records.first { rows -> rows.none { it.id==ticket.id && it.phase!="complete" } } }
        assertNotNull("Old receipt was not recorded; records=${submissions.records.first()} state=${model.uiState.value.bindingState}",settled)
        assertEquals(ticket.text,model.uiState.value.draftMessage)
        assertEquals(ticket.text,preferences.getDraftMessage(ticket.draftKey))
    }

    @Test fun deliberatelyClearingThePendingDraftSurvivesAnotherReceiptAndViewRecreation()=runBlocking<Unit> {
        preferences.setDraftMessage(ticket.draftKey,ticket.text);submissions.begin(ticket)
        val first=model();await { first.uiState.value.isSending }
        first.updateDraft("")
        withTimeout(10_000) { submissions.records.first { it.single().draftEdited } }
        submissions.checking(ticket.id)
        assertEquals("",first.uiState.value.draftMessage)
        models.first().clear()
        val restored=model(draft="")
        await { restored.uiState.value.isSending }
        receipt();withTimeout(10_000) { submissions.records.first { it.isEmpty() } }
        assertEquals("",restored.uiState.value.draftMessage)
        assertEquals("",preferences.getDraftMessage(ticket.draftKey))
    }
}
