package com.agentshell.data.repository

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        storeScope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Dispatchers.resetMain()
    }

    private fun repository() = ChatActivityRepository(
        app, preferences, HostRepository(hostDao, preferences), WebSocketService(client), client,
    ).also { repositories.add(it); it.start() }

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
}
