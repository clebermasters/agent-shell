package com.agentshell.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.core.power.BackgroundWorkPolicy
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.CachedHistorySync
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.remote.SessionSocket
import com.agentshell.data.remote.WebSocketService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LegacyArchiveSchedulingTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val server = MockWebServer()
    private val target = ChatTarget.create("host", "terminal", 0)
    private lateinit var preferences: PreferencesDataStore
    private lateinit var database: AppDatabase
    private lateinit var repository: OfflineCacheRepository
    private lateinit var attachments: OfflineAttachmentStore
    private var socket: SessionSocket? = null

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, "legacy-${directory.root.name}.db").build()
    private fun repository(db: AppDatabase) = OfflineCacheRepository(db, HostRepository(db.hostDao(), preferences), WebSocketService(client), client, attachments)
    private fun message(index: Int, prefix: String = "m") = ChatMessage("$prefix$index", "assistant", "Message $index", index.toLong())
    private fun wire(message: ChatMessage) = JSONObject().put("id", message.id).put("role", "assistant")
        .put("content", message.content).put("timestamp", message.timestamp)

    @Before fun setup() {
        preferences = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = preferencesScope,
            produceFile = { File(directory.root, "legacy.preferences_pb") }))
        attachments = OfflineAttachmentStore(context, client, BackgroundWorkPolicy().apply { updateDevice(connected = false) })
        database = openDatabase()
        repository = repository(database)
    }

    @After fun cleanup() = runBlocking {
        repository.close(); attachments.close(); socket?.dispose()
        preferencesScope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        context.deleteDatabase("legacy-${directory.root.name}.db")
        server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        Unit
    }

    private suspend fun connect(listener: WebSocketListener): SessionSocket {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(listener)
        }
        server.start()
        return SessionSocket(client).also {
            socket = it
            it.connect("ws://localhost:${server.port}/ws")
            withTimeout(5_000) { it.isConnected.first { connected -> connected } }
        }
    }

    @Test fun oldProtocolResumesAfterRestartAndAppendsWithoutRestartingOlderPages() = runBlocking {
        val source = AtomicReference((0 until 2503).map { message(it) })
        val identity = AtomicReference("conversation")
        val offsets = CopyOnWriteArrayList<Int>()
        val connection = connect(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val command = JSONObject(text)
                val type = command.optString("type")
                if (type !in setOf("watch-chat-log", "load-more-chat-history")) return
                val all = source.get()
                val offset = command.optInt("offset", 0)
                if (type == "load-more-chat-history") offsets.add(offset)
                val end = (all.size - offset).coerceAtLeast(0)
                val start = (end - command.optInt("limit", 30)).coerceAtLeast(0)
                val rows = JSONArray().also { array -> all.subList(start, end).forEach { array.put(wire(it)) } }
                webSocket.send(JSONObject().put("type", if (type == "watch-chat-log") "chat-history" else "chat-history-chunk")
                    .put("sessionName", "terminal").put("windowIndex", 0).put("conversationKey", identity.get())
                    .put("messages", rows).put("hasMore", start > 0).put("totalCount", all.size).toString())
            }
        })
        assertEquals(ArchiveSyncResult.Continue, repository.syncHistory(connection, target))
        assertEquals(630, database.offlineCacheDao().historySync(target.key)!!.offset)
        repository.close(); database.close()
        database = openDatabase(); repository = repository(database)
        var finished = false
        repeat(8) {
            if (finished) return@repeat
            val before = database.offlineCacheDao().historySync(target.key)!!.offset
            source.set(source.get() + message(source.get().size))
            offsets.clear()
            finished = repository.syncHistory(connection, target) == ArchiveSyncResult.Complete
            assertEquals("append growth rebases the saved cursor", before + 1, offsets.first())
        }
        assertTrue(finished)
        assertEquals(source.get().map { it.id }, repository.history(target)!!.messages.map { it.id })
        // A truncation must restart reconciliation, not skip directly past the new end.
        source.set(source.get().take(100))
        offsets.clear()
        assertEquals(ArchiveSyncResult.Complete, repository.syncHistory(connection, target))
        assertEquals(30, offsets.first())
        assertEquals(source.get().map { it.id }, repository.history(target)!!.messages.map { it.id })
        // A replacement conversation cannot inherit the previous conversation's offset.
        identity.set("replacement")
        source.set((0 until 80).map { message(it, "replacement-") })
        assertEquals(ArchiveSyncResult.Complete, repository.syncHistory(connection, target))
        assertEquals(source.get().map { it.id }, repository.history(target)!!.messages.map { it.id })
    }

    @Test fun malformedSnapshotsPreserveRowsAndCursorButExplicitEmptySnapshotsCanClear() = runBlocking {
        val template = AtomicReference(JSONObject())
        val connection = connect(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val command = JSONObject(text)
                val type = command.optString("type")
                val response = when (type) {
                    "get-history-state" -> JSONObject().put("type", "history-state").put("available", true).put("revision", "r1")
                    "get-history-page" -> JSONObject(template.get().toString()).put("type", "history-page").put("revision", "r1")
                    "watch-chat-log" -> JSONObject(template.get().toString()).put("type", "chat-history")
                        .put("sessionName", "terminal").put("windowIndex", 0).put("conversationKey", "conversation")
                    else -> return
                }
                response.put("requestId", command.optString("requestId"))
                webSocket.send(response.toString())
            }
        })
        val saved = (0 until 3).map { message(it) }
        val cursor = CachedHistorySync(target.key, "r0", 630, "m0", true)
        suspend fun reset() {
            repository.saveHistory(target, saved, true, "conversation")
            database.offlineCacheDao().saveHistorySync(cursor)
        }
        val malformed = listOf(
            JSONObject().put("hasMore", false),
            JSONObject().put("messages", "bad").put("hasMore", false),
            JSONObject().put("messages", JSONArray().put(7)).put("hasMore", false),
            JSONObject().put("messages", JSONArray().put(JSONObject())).put("hasMore", false),
            JSONObject().put("messages", JSONArray()),
            JSONObject().put("messages", JSONArray()).put("hasMore", "false"),
            JSONObject().put("entries", JSONArray().put(7)).put("hasMore", false),
            JSONObject().put("entries", JSONArray().put(JSONObject().put("key", "key").put("position", 0).put("message", JSONObject()))).put("hasMore", false),
        )
        reset()
        for (bad in malformed) {
            template.set(bad)
            assertTrue(repository.syncHistoryIncremental(connection, target) is ArchiveSyncResult.Retry)
            assertEquals(saved.map { it.id }, repository.history(target)!!.messages.map { it.id })
            assertEquals(cursor, database.offlineCacheDao().historySync(target.key))
        }
        template.set(JSONObject().put("messages", JSONArray()).put("hasMore", false))
        assertEquals(ArchiveSyncResult.Complete, repository.syncHistoryIncremental(connection, target))
        assertTrue(repository.history(target)!!.messages.isEmpty())
        reset()
        for (bad in malformed) {
            template.set(bad)
            assertTrue(repository.syncHistory(connection, target) is ArchiveSyncResult.Retry)
            assertEquals(saved.map { it.id }, repository.history(target)!!.messages.map { it.id })
            assertEquals(cursor, database.offlineCacheDao().historySync(target.key))
        }
        template.set(JSONObject().put("messages", JSONArray()).put("hasMore", false))
        assertEquals(ArchiveSyncResult.Complete, repository.syncHistory(connection, target))
        assertTrue(repository.history(target)!!.messages.isEmpty())
    }
}
