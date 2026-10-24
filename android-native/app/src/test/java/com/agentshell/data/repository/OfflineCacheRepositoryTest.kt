package com.agentshell.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.CachedSession
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineCacheRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { PreferencesDataStore(PreferenceDataStoreFactory.create(
        scope = storeScope, produceFile = { File(directory.root, "cache.preferences_pb") },
    )) }
    private val client = OkHttpClient()
    private val attachments by lazy { OfflineAttachmentStore(context, client) }
    private val repositories = mutableListOf<OfflineCacheRepository>()
    private val databases = mutableListOf<AppDatabase>()

    private fun database(statements: MutableList<String>? = null): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, "offline-test-${directory.root.name}.db")
        if (statements != null) builder.setQueryCallback({ sql, _ -> statements.add(sql) }, Executor { it.run() })
        return builder.build().also { databases.add(it) }
    }

    private fun repository(db: AppDatabase) = OfflineCacheRepository(
        db, HostRepository(db.hostDao(), preferences), WebSocketService(client), client, attachments,
    ).also { repositories.add(it) }

    private fun message(id: String, time: Long, text: String = id) = ChatMessage(id, "assistant", text, time)
    private val target = ChatTarget.create("server-a", "session", 1)

    @After fun cleanup() {
        repositories.forEach { it.close() }
        attachments.close()
        runBlocking { storeScope.coroutineContext[Job]?.cancelAndJoin() }
        databases.forEach { if (it.isOpen) it.close() }
        context.deleteDatabase("offline-test-${directory.root.name}.db")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun sessionsAndCompleteHistorySurviveDatabaseReopenWithoutNetwork() = runTest {
        val first = database()
        val repo = repository(first)
        val rows = listOf(CachedSession("server-a", "tmux", "session", 0, "{}"))
        first.offlineCacheDao().replaceSessions("server-a", "tmux", rows)
        // More than the old remote page size, including a structured attachment.
        val messages = (0 until 350).map { message("m$it", it.toLong()) } + ChatMessage(
            "file", "user", "attachment", 400,
            blocks = listOf(ChatBlock(type = "file", id = "attachment-id", filename = "notes.txt")),
        )
        repo.saveHistory(target, messages, true, "conversation-a", "codex")
        repo.close()
        first.close()
        val reopened = database()
        val restored = repository(reopened)
        assertEquals(rows, reopened.offlineCacheDao().observeSessions("server-a").first())
        assertEquals(messages, restored.history(target)?.messages)
        assertTrue(restored.history(target)!!.conversation.complete)
        assertNull(restored.history(target.copy(hostId = "server-b")))
        assertNull(restored.history(target.copy(windowIndex = 2)))
    }

    @Test fun partialRefreshRetainsOlderPagesAndUpdatesStableMessageIds() = runTest {
        val repo = repository(database())
        repo.saveHistory(target, listOf(message("a", 1), message("b", 2)), true, "one")
        repo.saveHistory(target, listOf(message("b", 2, "updated"), message("c", 3)), false, "one")
        assertEquals(listOf(message("a", 1), message("b", 2, "updated"), message("c", 3)), repo.history(target)?.messages)
        repo.saveHistory(target, listOf(message("different", 4)), false, "two")
        assertEquals(listOf(message("different", 4)), repo.history(target)?.messages)
    }

    @Test fun authoritativeEmptyHistoryClearsMessagesWithoutDeletingOtherServers() = runTest {
        val db = database()
        val repo = repository(db)
        val other = target.copy(hostId = "server-b")
        repo.saveHistory(target, listOf(message("a", 1)), true)
        repo.saveHistory(other, listOf(message("b", 2)), true)
        repo.saveHistory(target, emptyList(), true)
        assertEquals(emptyList<ChatMessage>(), repo.history(target)?.messages)
        assertEquals(listOf(message("b", 2)), repo.history(other)?.messages)
        db.offlineCacheDao().replaceSessions("server-a", "tmux", listOf(CachedSession("server-a", "tmux", "old", 0, "{}")))
        db.offlineCacheDao().replaceSessions("server-b", "tmux", listOf(CachedSession("server-b", "tmux", "other", 0, "{}")))
        db.offlineCacheDao().replaceSessions("server-a", "tmux", emptyList())
        assertTrue(db.offlineCacheDao().observeSessions("server-a").first().isEmpty())
        assertEquals(1, db.offlineCacheDao().observeSessions("server-b").first().size)
    }

    @Test fun smallLiveUpdatesNeverDecodeDeleteOrRewriteTheStoredTranscript() = runBlocking {
        val statements = Collections.synchronizedList(mutableListOf<String>())
        val db = database(statements)
        val repo = repository(db)
        val archive = (0 until 2_000).map { message("m$it", it.toLong()) }
        repo.saveHistory(target, archive, true, "conversation")
        statements.clear()

        repo.saveHistory(target, listOf(message("m1999", 1999, "updated")), false, "conversation")

        val changedSql = synchronized(statements) { statements.toList() }
        assertEquals(1, changedSql.count { it.startsWith("INSERT OR REPLACE INTO `cached_chat_messages`") })
        assertFalse(changedSql.any { it.startsWith("DELETE FROM cached_chat_messages") })
        assertFalse(changedSql.any { it.startsWith("SELECT * FROM cached_chat_messages") && !it.contains("messageId IN") })
        statements.clear()
        repo.saveHistory(target, listOf(message("m1999", 1999, "updated")), false, "conversation")
        val unchangedSql = synchronized(statements) { statements.toList() }
        assertFalse(unchangedSql.any { it.startsWith("INSERT OR REPLACE INTO `cached_chat_messages`") || it.startsWith("DELETE FROM cached_chat_messages") })
        assertEquals(2_000, db.offlineCacheDao().messageCount(target.key))
    }

    @Test fun localHistoryPagesAreBoundedIndexedAndReachTheEntireUnlimitedArchive() = runBlocking {
        val db = database()
        val repo = repository(db)
        val archive = (0 until 2_005).map { message("m$it", it.toLong()) }
        repo.saveHistory(target, archive, true)
        val found = mutableListOf<ChatMessage>()
        var position: Int? = null
        var id: String? = null
        do {
            val page = repo.historyPage(target, position, 100, id)!!
            assertTrue(page.messages.size <= 100)
            assertEquals(2_005, page.totalMessageCount)
            found.addAll(0, page.messages)
            position = page.oldestPosition
            id = page.messages.firstOrNull()?.id
        } while (page.hasMore)
        assertEquals(archive, found)
        val plan = db.openHelper.writableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM cached_chat_messages WHERE targetKey = 'test' AND position < 100 ORDER BY position DESC LIMIT 100",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
        assertTrue(plan.any { it.contains("index_cached_chat_messages_targetKey_position") })
        assertFalse(plan.any { it.contains("TEMP B-TREE") })
    }

    @Test fun aFarAheadHeadInvalidatesCompletenessAndStableIdCursorFollowsInsertedGap() = runBlocking {
        val repo = repository(database())
        repo.saveHistory(target, (0 until 10).map { message("m$it", it.toLong()) }, true, "same")
        repo.saveHistory(target, (20 until 30).map { message("m$it", it.toLong()) }, false, "same")
        val head = repo.historyPage(target, limit = 10)!!
        assertFalse(head.conversation.complete)
        val gap = (9..20).map { message("m$it", it.toLong()) }
        repo.saveHistory(target, gap, false, "same")
        val older = repo.historyPage(target, head.oldestPosition, 100, head.messages.first().id)!!
        assertEquals((0 until 20).map { "m$it" }, older.messages.map { it.id })
        assertEquals((0 until 30).map { "m$it" }, repo.history(target)!!.messages.map { it.id })
    }

    @Test fun queuedAuthoritativeClearCannotBeDroppedByALaterPartialSnapshot() = runBlocking {
        val repo = repository(database())
        repo.saveHistory(target, listOf(message("old", 1)), true)
        repo.saveHistoryAsync(target, listOf(message("old", 1), message("pending", 2)), false)
        repo.saveHistoryAsync(target, emptyList(), true)
        repo.saveHistoryAsync(target, listOf(message("new", 3)), false)
        withTimeout(5_000) {
            while (repo.history(target)?.messages?.map { it.id } != listOf("new")) delay(10)
        }
        assertEquals(listOf("new"), repo.history(target)!!.messages.map { it.id })
    }

    @Test fun backgroundSyncBridgesLargeGapsAndSkipsOldPagesWhenTheHeadIsUnchanged() = runBlocking {
        val server = MockWebServer()
        val attached = AtomicBoolean(false)
        val terminalHeads = AtomicInteger()
        val terminalOlderPages = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val command = JSONObject(text)
                    val type = command.getString("type")
                    val response = when (type) {
                        "list-sessions" -> JSONObject().put("type", "sessions-list").put("sessions", JSONArray()
                            .put(JSONObject().put("name", "terminal").put("windows", 1).put("attached", attached.get())))
                        "acp-list-sessions" -> JSONObject().put("type", "acp-sessions-listed").put("sessions", JSONArray()
                            .put(JSONObject().put("sessionId", "codex:direct").put("cwd", "/project")))
                        "list-windows" -> JSONObject().put("type", "windows-list").put("sessionName", "terminal")
                            .put("windows", JSONArray().put(JSONObject().put("index", 3)))
                        "watch-chat-log", "watch-acp-chat-log", "load-more-bound-chat-history", "load-more-chat-history" -> {
                            val direct = command.optString("sessionId").isNotBlank() || command.optString("sessionName").startsWith("acp_")
                            if (!direct) {
                                if (type.startsWith("watch")) terminalHeads.incrementAndGet() else terminalOlderPages.incrementAndGet()
                            }
                            val total = if (direct) 251 else 503
                            val offset = command.optInt("offset", 0)
                            val end = (total - offset).coerceAtLeast(0)
                            val start = (end - command.optInt("limit", 200)).coerceAtLeast(0)
                            val history = JSONArray()
                            for (index in start until end) history.put(JSONObject().put("id", "m$index").put("role", "assistant").put("timestamp", index).put("content", "Message $index"))
                            JSONObject().put("type", if (type.startsWith("watch")) "chat-history" else "chat-history-chunk")
                                .put("sessionName", if (direct) "acp_codex:direct" else "terminal")
                                .put("windowIndex", if (direct) 0 else 3).put("messages", history)
                                .put("hasMore", start > 0).put("totalCount", total).also {
                                    if (!direct) it.put("bindingId", "binding-1").put("conversationKey", "conversation-1")
                                }
                        }
                        "ping" -> JSONObject().put("type", "pong")
                        else -> null
                    }
                    if (response != null) webSocket.send(response.toString())
                }
            })
        }
        server.start()
        val db = database()
        val hosts = HostRepository(db.hostDao(), preferences)
        val host = Host("network-host", "Local test", "localhost", server.port, tls = false)
        hosts.saveHost(host)
        hosts.selectHost(host.id)
        val socket = WebSocketService(client)
        val repo = OfflineCacheRepository(db, hosts, socket, client, attachments).also { repositories.add(it) }
        val terminal = ChatTarget.create(host.id, "terminal", 3)
        val direct = ChatTarget.create(host.id, "codex:direct", isAcp = true)
        // The remote has hundreds of messages beyond this formerly complete
        // baseline. A cached latest page alone must not hide the intervening gap.
        repo.saveHistory(terminal, (0 until 10).map { message("m$it", it.toLong()) }, true, "conversation-1")
        try {
            repo.sessions.first { it.hostId == host.id }
            socket.connect("${host.wsUrl}/ws", null)
            withTimeout(10_000) { socket.connectionStatus.first { socket.isConnected } }
            socket.send(mapOf("type" to "list-sessions"))
            socket.send(mapOf("type" to "acp-list-sessions"))
            withTimeout(20_000) {
                while (db.offlineCacheDao().messageCount(terminal.key) != 503 ||
                    db.offlineCacheDao().messageCount(direct.key) != 251 ||
                    db.offlineCacheDao().conversation(terminal.key)?.complete != true ||
                    db.offlineCacheDao().conversation(direct.key)?.complete != true
                ) delay(25)
            }
            assertEquals((0 until 503).map { "m$it" }, repo.history(terminal)!!.messages.map { it.id })
            assertTrue(repo.history(direct)!!.conversation.complete)
            assertTrue(repo.history(terminal)!!.conversation.complete)
            val olderRequests = terminalOlderPages.get()
            val headRequests = terminalHeads.get()
            attached.set(true)
            socket.send(mapOf("type" to "list-sessions"))
            withTimeout(10_000) { while (terminalHeads.get() <= headRequests) delay(25) }
            delay(500)
            assertEquals(olderRequests, terminalOlderPages.get())
            socket.disconnect()
            assertEquals("terminal", repo.sessions.first { it.tmux.isNotEmpty() }.tmux.single().name)
            assertEquals(503, repo.history(terminal)!!.messages.size)
        } finally {
            repo.close()
            socket.disconnect()
            server.close()
        }
    }

    @Test fun migrationFromVersionEightPreservesConfiguredHosts() = runBlocking {
        val db = database()
        val host = Host("saved-host", "Saved server", "localhost", 4010)
        db.hostDao().insert(host)
        val path = requireNotNull(db.openHelper.writableDatabase.path)
        db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE cached_sessions")
            it.execSQL("DROP TABLE cached_conversations")
            it.execSQL("DROP TABLE cached_chat_messages")
            it.version = 8
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, "offline-test-${directory.root.name}.db")
            .addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build().also { databases.add(it) }
        assertEquals(host, upgraded.hostDao().getById(host.id))
        assertTrue(upgraded.offlineCacheDao().observeSessions(host.id).first().isEmpty())
    }

    @Test fun migrationFromVersionNineAddsPagingIndexWithoutLosingCachedHistory() = runBlocking {
        val db = database()
        val repo = repository(db)
        repo.saveHistory(target, listOf(message("saved", 1)), true)
        repo.close()
        val path = requireNotNull(db.openHelper.writableDatabase.path)
        db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_cached_chat_messages_targetKey_position")
            it.version = 9
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, "offline-test-${directory.root.name}.db")
            .addMigrations(AppDatabase.MIGRATION_9_10).build().also { databases.add(it) }
        val page = repository(upgraded).historyPage(target)!!
        assertEquals(listOf(message("saved", 1)), page.messages)
        assertFalse(page.conversation.complete)
    }

    @Test fun attachmentBytesRemainReadableAfterServerStopsAndStoreRestarts() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("offline attachment content"))
        server.start()
        val base = server.url("/api/chat/files").toString().trimEnd('/')
        val url = "$base/file-id"
        val message = ChatMessage("file-message", "user", null, 1,
            blocks = listOf(ChatBlock(type = "file", id = "file-id", filename = "notes.txt")))
        try {
            attachments.enqueue(base, listOf(message))
            withTimeout(10_000) { while (OfflineAttachmentStore.cachedFile(context, url) == null) delay(25) }
            server.close()
            attachments.close()
            assertEquals("offline attachment content", OfflineAttachmentStore.cachedFile(context, url)!!.readText())
            assertTrue(OfflineAttachmentStore.resolve(context, url).startsWith("file:"))
            // The URL hash scopes identically named attachment IDs to their server.
            assertNull(OfflineAttachmentStore.cachedFile(context, "http://other-server/api/chat/files/file-id"))
        } finally {
            OfflineAttachmentStore.cachedFile(context, url)?.delete()
        }
    }
}
