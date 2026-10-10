package com.agentshell.data.repository

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Notification
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.AgentActivityStatus
import com.agentshell.data.model.Host
import com.agentshell.data.model.ChatBindingState
import com.agentshell.data.model.ConversationBinding
import com.agentshell.data.remote.WebSocketService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatActivityRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var app: Application
    private val hostDao = object : HostDao {
        override fun getAll() = flowOf(emptyList<Host>())
        override suspend fun getById(id: String): Host? = null
        override suspend fun insert(host: Host) = Unit
        override suspend fun delete(host: Host) = Unit
        override suspend fun deleteById(id: String) = Unit
    }
    private lateinit var preferences: PreferencesDataStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var manager: NotificationManager
    private val repositories = mutableListOf<ChatActivityRepository>()
    private val client = OkHttpClient()
    private val target = ChatTarget.create("host-a", "session", 1)
    private val old = ChatMessage("old", "assistant", "Old reply", 100)
    private val new = ChatMessage("new", "assistant", "New reply", 200)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannel(app)
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferencesDataStore(PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(directory.root, "chat.preferences_pb") },
        ))
    }

    @After fun teardown() {
        repositories.forEach { it.close() }
        // Finish DataStore I/O before JUnit removes the temporary directory.
        runBlocking { storeScope.coroutineContext[Job]?.cancelAndJoin() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Dispatchers.resetMain()
    }

    private fun repository() = ChatActivityRepository(
        app, preferences, HostRepository(hostDao, preferences), WebSocketService(client), client,
    ).also { repositories.add(it); it.start() }

    private fun link(id: String, key: String) = ChatBindingState("bound", "token", "codex", ConversationBinding(id, id, key, "hook"), emptyList(), "linked")

    @Test fun independent_same_folder_conversations_keep_separate_unread_state_and_reject_foreign_packets() = runTest {
        val repo = repository()
        val first = target.copy(cwd = "/same/project")
        val second = target.copy(sessionName = "second", cwd = "/same/project")
        repo.register(first); repo.register(second)
        repo.linkConversation(first, link("first", "conversation:first"))
        repo.linkConversation(second, link("second", "conversation:second"))
        repo.history(first, listOf(old)); repo.history(second, listOf(old))
        val packet = mapOf<String, Any?>("type" to "chat-history", "sessionName" to first.sessionName, "windowIndex" to first.windowIndex,
            "bindingId" to "second", "conversationKey" to "conversation:second", "messages" to emptyList<Any>())
        repo.handleEvent(first.hostId, packet, first)
        assertEquals("old", repo.states.value.getValue(first.key).latestReceived?.id)
        repo.history(first, listOf(old, new))
        assertEquals(1, repo.states.value.getValue(first.key).unread.size)
        assertTrue(repo.states.value.getValue(second.key).unread.isEmpty())
    }

    @Test fun resume_resets_old_unread_receipts_but_reconnecting_same_conversation_preserves_them() = runTest {
        val repo = repository(); repo.register(target)
        repo.linkConversation(target, link("old-lease", "conversation:A"))
        repo.history(target, listOf(old)); repo.history(target, listOf(old, new))
        repo.linkConversation(target, link("new-lease", "conversation:A"))
        assertEquals(1, repo.states.value.getValue(target.key).unread.size)
        repo.linkConversation(target, link("resumed", "conversation:B"))
        assertTrue(repo.states.value.getValue(target.key).unread.isEmpty())
        assertFalse(repo.states.value.getValue(target.key).initialized)
        assertEquals("conversation:B", repo.states.value.getValue(target.key).conversationKey)
    }

    @Test fun late_completion_from_previous_conversation_does_not_notify_the_resumed_chat() = runTest {
        val repo = repository(); repo.register(target)
        repo.linkConversation(target, link("old", "conversation:A"))
        repo.linkConversation(target, link("new", "conversation:B"))
        val message = signalActivity("idle", 3, finished = true).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val state = (message["state"] as Map<String, Any?>) + ("conversationKey" to "conversation:A")
        message["state"] = state
        repo.handleAgentActivity(target.hostId, message)
        repo.awaitAgentSignals()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun activity(session: String, window: Int, status: String, sequence: Long = 1) = mapOf(
        "type" to "chat-activity", "sessionName" to session, "windowIndex" to window, "paneId" to "%1",
        "state" to mapOf("status" to status, "source" to "tmux-status", "confidence" to "inferred", "observerId" to "one", "sequence" to sequence, "observedAt" to 1000L),
    )

    private fun signalActivity(status: String, sequence: Long, finished: Boolean = false, quiet: Boolean = false) = mapOf(
        "type" to "chat-activity", "sessionName" to target.sessionName, "windowIndex" to target.windowIndex,
        "state" to mapOf("status" to status, "source" to if (quiet || status == "recent-activity") "tmux-activity" else "codex-log", "confidence" to if (quiet || status == "recent-activity") "estimated" else "reported",
            "observerId" to "signal", "sequence" to sequence, "observedAt" to 61_000L, "startedAt" to if (status == "working") 1000L else null,
            "turnId" to "turn-one", "finishedAt" to if (finished) 61_000L else null, "completionReason" to if (finished) "completed" else null, "quietAt" to if (quiet) 61_000L else null),
    )

    @Test fun finishedAlertIsDurableAndDoesNotRepeatAfterReconnectOrRestart() = runTest {
        val repo = repository(); repo.register(target)
        repo.handleAgentActivity(target.hostId, signalActivity("working", 1))
        repo.handleAgentActivity(target.hostId, signalActivity("idle", 2, finished = true))
        repo.awaitAgentSignals()
        assertEquals(NotificationHelper.FINISHED_CHANNEL_ID, shadowOf(manager).allNotifications.single().channelId)
        manager.cancelAll()
        repo.handleAgentActivity(target.hostId, signalActivity("idle", 3, finished = true))
        repo.awaitAgentSignals()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        repo.close()
        val restored = repository(); restored.register(target)
        restored.handleAgentActivity(target.hostId, signalActivity("idle", 4, finished = true))
        restored.awaitAgentSignals()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun quietTransitionUsesItsOwnChannelOnce() = runTest {
        val repo = repository(); repo.register(target)
        repo.handleAgentActivity(target.hostId, signalActivity("recent-activity", 1))
        repo.handleAgentActivity(target.hostId, signalActivity("unknown", 2, quiet = true))
        repo.awaitAgentSignals()
        assertEquals(NotificationHelper.QUIET_CHANNEL_ID, shadowOf(manager).allNotifications.single().channelId)
        manager.cancelAll()
        repo.handleAgentActivity(target.hostId, signalActivity("unknown", 3, quiet = true))
        repo.awaitAgentSignals()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun initialQuietSnapshotAndDisabledCompletionDoNotAlert() = runTest {
        preferences.setAgentFinishedEnabled(false)
        val repo = repository(); repo.register(target)
        repo.handleAgentActivity(target.hostId, signalActivity("unknown", 1, quiet = true))
        repo.handleAgentActivity(target.hostId, signalActivity("working", 2))
        repo.handleAgentActivity(target.hostId, signalActivity("idle", 3, finished = true))
        repo.awaitAgentSignals()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun activityIsScopedToItsHostAndWindow() = runTest {
        val repo = repository()
        repo.handleAgentActivity("host-a", activity("session", 1, "working"))
        repo.handleAgentActivity("host-b", activity("session", 1, "idle"))
        repo.handleAgentActivity("host-a", activity("session", 2, "waiting"))
        assertEquals(AgentActivityStatus.WORKING, repo.agentActivities.value.getValue(target.key).status)
        assertEquals(AgentActivityStatus.IDLE, repo.agentActivities.value.getValue(target.copy(hostId = "host-b").key).status)
        assertEquals(AgentActivityStatus.WAITING, repo.agentActivities.value.getValue(target.copy(windowIndex = 2).key).status)
    }

    @Test fun panelAndBackgroundSocketsCannotAttributeAnotherPanesEventToTheirTarget() = runTest {
        val repo = repository()
        repo.handleAgentActivity("host-a", activity("other", 1, "working"), target)
        assertTrue(repo.agentActivities.value.isEmpty())
    }

    @Test fun disconnectClearsWorkingAndReconnectSnapshotRestoresIt() = runTest {
        val repo = repository()
        repo.handleAgentActivity("host-a", activity("session", 1, "working"))
        repo.invalidateActivity(target)
        assertEquals(AgentActivityStatus.UNKNOWN, repo.agentActivities.value.getValue(target.key).status)
        repo.handleAgentActivity("host-a", activity("session", 1, "working", 2))
        assertEquals(AgentActivityStatus.WORKING, repo.agentActivities.value.getValue(target.key).status)
    }

    @Test fun legacyDirectProtocolProvidesFallbackAndCompletion() = runTest {
        val repo = repository()
        val direct = ChatTarget.create("host-a", "codex:one", isAcp = true)
        repo.handleAgentActivity("host-a", mapOf("type" to "acp-message-chunk", "sessionId" to "codex:one"))
        assertEquals(AgentActivityStatus.WORKING, repo.agentActivities.value.getValue(direct.key).status)
        repo.handleAgentActivity("host-a", mapOf("type" to "acp-prompt-done", "sessionId" to "codex:one", "stopReason" to "completed"))
        assertEquals(AgentActivityStatus.IDLE, repo.agentActivities.value.getValue(direct.key).status)
    }

    @Test fun unreadReplyPersistsAcrossRepositoryRestartWithoutRealertingOnHistoryReplay() = runTest {
        val first = repository()
        first.register(target)
        first.history(target, listOf(old))
        first.history(target, listOf(old, new))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        first.close()
        manager.cancelAll()
        val reopened = repository()
        val restored = reopened.register(target)
        assertEquals("new", restored.unread.single().id)
        reopened.history(target, listOf(old, new))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        reopened.readThrough(target, listOf(old, new))
        assertTrue(reopened.states.value.getValue(target.key).unread.isEmpty())
    }

    @Test fun readingOneChatDoesNotClearAnotherWindowOrHost() = runTest {
        val repo = repository()
        val other = target.copy(hostId = "host-b", windowIndex = 2)
        for (chat in listOf(target, other)) {
            repo.register(chat)
            repo.history(chat, listOf(old))
            repo.history(chat, listOf(old, new))
        }
        assertEquals(2, shadowOf(manager).allNotifications.size)
        repo.readThrough(target, listOf(old, new))
        assertTrue(repo.states.value.getValue(target.key).unread.isEmpty())
        assertEquals("new", repo.states.value.getValue(other.key).unread.single().id)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun foregroundViewingSuppressesSoundUntilTheChatIsNoLongerVisible() = runTest {
        val repo = repository()
        repo.register(target)
        repo.history(target, listOf(old))
        repo.setViewing("screen", target, true)
        repo.history(target, listOf(old, new))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        repo.readThrough(target, listOf(old, new))
        repo.setViewing("screen", target, false)
        repo.history(target, listOf(old, new, ChatMessage("next", "assistant", "Another reply", 300)))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun disablingAlertsPreservesUnreadTracking() = runTest {
        preferences.setChatNotificationsEnabled(false)
        val repo = repository()
        repo.register(target)
        repo.history(target, listOf(old))
        repo.history(target, listOf(old, new))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals("new", repo.states.value.getValue(target.key).unread.single().id)
    }

    @Test fun aSecondChatRequestsSoundWhileTheFirstChatsNotificationRemainsUnread() = runTest {
        val repo = repository()
        val second = target.copy(sessionName = "second chat")
        for (chat in listOf(target, second)) {
            repo.register(chat)
            repo.history(chat, listOf(old))
        }
        repo.history(target, listOf(old, new))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        repo.history(second, listOf(old, new))
        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        assertTrue(notifications.all { it.groupAlertBehavior == Notification.GROUP_ALERT_ALL })
        assertTrue(notifications.all { it.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0 })
    }

    @Test fun aSilentStreamingPreviewMustNotSuppressTheLaterAudibleCompletion() = runTest {
        preferences.setAgentFinishedEnabled(false)
        val repo = repository()
        val acp = ChatTarget.create("host-a", "codex:background", isAcp = true)
        for (chat in listOf(target, acp)) {
            repo.register(chat)
            repo.history(chat, listOf(old))
            repo.history(chat, listOf(old, new))
        }
        repo.handleEvent("host-a", mapOf("type" to "acp-message-chunk", "sessionId" to acp.sessionName, "content" to "A new streamed reply"))
        advanceTimeBy(800)
        runCurrent()
        fun acpNotification() = shadowOf(manager).allNotifications.single {
            NotificationHelper.chatTarget(shadowOf(it.contentIntent).savedIntent) == acp
        }
        assertEquals(Notification.GROUP_ALERT_SUMMARY, acpNotification().groupAlertBehavior)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        repo.handleEvent("host-a", mapOf("type" to "acp-prompt-done", "sessionId" to acp.sessionName))
        assertEquals(2, shadowOf(manager).allNotifications.size)
        assertEquals(Notification.GROUP_ALERT_ALL, acpNotification().groupAlertBehavior)
    }

    @Test fun anAudibleStreamingPreviewKeepsItsCompletionUpdateSilent() = runTest {
        preferences.setAgentFinishedEnabled(false)
        val repo = repository()
        val acp = ChatTarget.create("host-a", "codex:background", isAcp = true)
        repo.register(acp)
        repo.history(acp, listOf(old))
        repo.handleEvent("host-a", mapOf("type" to "acp-message-chunk", "sessionId" to acp.sessionName, "content" to "A new streamed reply"))
        advanceTimeBy(800)
        runCurrent()
        assertEquals(Notification.GROUP_ALERT_ALL, shadowOf(manager).allNotifications.single().groupAlertBehavior)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        repo.handleEvent("host-a", mapOf("type" to "acp-prompt-done", "sessionId" to acp.sessionName))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, shadowOf(manager).allNotifications.single().groupAlertBehavior)
    }
}
