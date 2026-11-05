package com.agentshell.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatCatalogTrafficTest {
    @get:Rule val directory = TemporaryFolder()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Before fun setup() { Dispatchers.setMain(main) }
    @After fun cleanup() { Dispatchers.resetMain(); main.close() }

    private class Backend : AutoCloseable {
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val watches = AtomicInteger()
        val windowRequests = AtomicInteger()
        val closedMonitors = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    private var monitor = false
                    override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "watch-chat-log" -> { monitor = true; watches.incrementAndGet() }
                            "list-windows" -> {
                                windowRequests.incrementAndGet()
                                webSocket.send(JSONObject().put("type", "windows-list").put("sessionName", message.getString("sessionName"))
                                    .put("windows", JSONArray().put(JSONObject().put("index", 0))).toString())
                            }
                            "ping" -> webSocket.send("{\"type\":\"pong\"}")
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (monitor) closedMonitors.incrementAndGet()
                        sockets.remove(webSocket)
                    }
                    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { sockets.remove(webSocket) }
                })
            }
            start()
        }
        override fun close() { server.close() }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(8_000) {
        while (!predicate()) delay(10)
    }

    private suspend fun fixture(block: suspend (ChatActivityRepository, WebSocketService, HostRepository, Host, Backend, OkHttpClient) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { File(directory.newFolder(), "chat.preferences_pb") }))
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val hosts = HostRepository(db.hostDao(), store)
        val backend = Backend()
        val client = OkHttpClient()
        val host = Host("host", "Test server", "localhost", backend.server.port, tls = false)
        hosts.saveHost(host); hosts.selectHost(host.id)
        val primary = WebSocketService(client)
        val repo = ChatActivityRepository(app, store, hosts, primary, client)
        try {
            withContext(main) { repo.start(); primary.connect("${host.wsUrl}/ws") }
            await { primary.isConnected }
            block(repo, primary, hosts, host, backend, client)
        } finally {
            withContext(main) { repo.close(); primary.dispose() }
            await { backend.sockets.isEmpty() }
            backend.close(); db.close(); storeScope.coroutineContext[Job]?.cancelAndJoin()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private fun catalog(windows: Int = 1) = mapOf<String, Any?>("type" to "sessions-list", "sessions" to
        (0 until 8).map { mapOf("name" to "s$it", "created" to "creation", "windows" to windows, "tool" to "codex") })

    @Test fun repeatedCatalogsCannotFloodWindowQueriesOrDuplicateNotificationWatches() = runBlocking {
        fixture { repo, _, _, host, backend, _ ->
            withContext(main) { repo.handleEvent(host.id, catalog()) }
            await { backend.watches.get() == 8 }
            withContext(main) { repeat(1_000) { repo.handleEvent(host.id, catalog()) } }
            assertEquals(8, backend.watches.get())
            assertTrue("Initial discovery may race onOpen, but repeated catalogs must not issue thousands of queries",
                backend.windowRequests.get() <= 24)
            val before = backend.windowRequests.get()
            withContext(main) { repo.handleEvent(host.id, catalog(windows = 2)) }
            await { backend.windowRequests.get() >= before + 8 }
            assertEquals(8, backend.watches.get())
        }
    }

    @Test fun changingAnExistingHostsEndpointReplacesOldNotificationSocketsInEitherOrdering() = runBlocking {
        for (primaryFirst in listOf(false, true)) fixture { repo, primary, hosts, host, first, _ ->
            val second = Backend()
            try {
                withContext(main) { repo.handleEvent(host.id, catalog()) }
                await { first.watches.get() == 8 }
                val changed = host.copy(port = second.server.port)
                if (primaryFirst) {
                    withContext(main) { primary.connect("${changed.wsUrl}/ws") }
                    await { primary.isConnected }
                    hosts.saveHost(changed)
                } else {
                    hosts.saveHost(changed)
                    withContext(main) { primary.connect("${changed.wsUrl}/ws") }
                }
                await { second.watches.get() == 8 && first.closedMonitors.get() == 8 }
                assertEquals(8, second.watches.get())
            } finally {
                withContext(main) { repo.close(); primary.dispose() }
                await { second.sockets.isEmpty() }
                second.close()
            }
        }
    }
}
