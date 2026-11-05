package com.agentshell.core.diagnostics

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.remote.SessionSocket
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import androidx.core.content.FileProvider

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BatteryDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: Application

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        // Robolectric changes the app's data directory for each test, while
        // AndroidX caches provider roots statically for a real process lifetime.
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        BatteryDiagnostics.setEnabled(app, false)
        File(app.filesDir, "battery-diagnostics").deleteRecursively()
    }

    @After fun cleanup() = runBlocking {
        BatteryDiagnostics.setEnabled(app, false)
        exportedText() // Finish pending disk writes before Robolectric replaces its context.
        Unit
    }

    private suspend fun exportedText(): String {
        val chooser = BatteryDiagnostics.shareIntent(app)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return File(app.cacheDir, "battery-diagnostics/agentshell-battery-diagnostics.txt").readText()
    }

    @Test fun disabledByDefaultAndPreferenceSurvivesRepositoryRecreation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "diagnostics.preferences_pb") })
        try {
            assertFalse(PreferencesDataStore(store).batteryDiagnosticsEnabled.first())
            PreferencesDataStore(store).setBatteryDiagnosticsEnabled(true)
            assertTrue(PreferencesDataStore(store).batteryDiagnosticsEnabled.first())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun exportHasMetadataAndCountsButCannotContainPayloadsOrUnknownTypeNames() = runBlocking {
        BatteryDiagnostics.setEnabled(app, true)
        val payload = "secret-token=123 password=abc message=private conversation 🚀"
        BatteryDiagnostics.message(DiagnosticSocketRole.PRIMARY, true, payload, "secret-type-name")
        BatteryDiagnostics.event("private exception")
        val text = exportedText()
        assertTrue(text.contains("PRIMARY.other.rx_bytes=${payload.toByteArray(Charsets.UTF_8).size}"))
        assertTrue(text.contains("process_cpu_ms="))
        assertTrue(text.contains("uid_rx_bytes="))
        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("password"))
        assertFalse(text.contains("private conversation"))
        assertFalse(text.contains("secret-type-name"))
        assertFalse(text.contains("private exception"))
        BatteryDiagnostics.setEnabled(app, false)
        val stopped = exportedText()
        BatteryDiagnostics.message(DiagnosticSocketRole.PRIMARY, true, "another payload", "chat-history")
        assertEquals(stopped, exportedText())
    }

    @Test fun enabledDiagnosticsObserveARealSessionSocketWithoutChangingDelivery() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val socket = SessionSocket(client, DiagnosticSocketRole.OFFLINE_SYNC)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"type\":\"history-page\",\"content\":\"private-answer\"}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        BatteryDiagnostics.setEnabled(app, true)
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) { socket.messages.first { it["type"] == "history-page" } }
            socket.connect(server.url("/ws?token=private-auth").toString().replaceFirst("http:", "ws:"))
            assertEquals("private-answer", withTimeout(5_000) { received.await() }["content"])
            val text = exportedText()
            assertTrue(text.contains("sockets_OFFLINE_SYNC=1"))
            assertTrue(text.contains("OFFLINE_SYNC.history.rx_messages=1"))
            assertFalse(text.contains("private-auth"))
            assertFalse(text.contains("private-answer"))
            socket.dispose()
            assertTrue(exportedText().contains("sockets_OFFLINE_SYNC=0"))
        } finally {
            socket.dispose(); server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun rollingFilesKeepOnlyTheMostRecentTwoWindows() {
        val dir = temporary.newFolder()
        val window = "x".repeat(1_048_576)
        BatteryDiagnostics.append(dir, window)
        BatteryDiagnostics.append(dir, "second")
        assertEquals(window, dir.resolve("previous.log").readText())
        BatteryDiagnostics.append(dir, "y".repeat(1_048_576 - 6))
        BatteryDiagnostics.append(dir, "third")
        assertTrue(dir.resolve("previous.log").readText().startsWith("second"))
        assertEquals("third", dir.resolve("current.log").readText())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2_097_152)
        assertEquals(-1L, BatteryDiagnostics.delta(-1, 20))
        assertEquals(-1L, BatteryDiagnostics.delta(20, 10))
    }

    @Test fun httpBodyCountersDoNotReadOrLogUrlsHeadersOrBodyContents() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().eventListenerFactory(BatteryDiagnostics.httpListenerFactory).build()
        val upload = "private-upload"
        val download = "private-download"
        server.enqueue(MockResponse().setBody(download))
        server.start()
        BatteryDiagnostics.setEnabled(app, true)
        try {
            val request = Request.Builder().url(server.url("/api/files/private-filename?token=private-auth"))
                .header("Authorization", "Bearer private-key").post(upload.toRequestBody()).build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { assertEquals(download, it.body!!.string()) } }
            val text = exportedText()
            assertTrue(text.contains("HTTP.files.tx_bytes=${upload.length}"))
            assertTrue(text.contains("HTTP.files.rx_bytes=${download.length}"))
            for (secret in listOf(upload, download, "private-filename", "private-auth", "private-key")) assertFalse(text.contains(secret))
        } finally {
            server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun httpHeadersReportFailedAndSuccessfulZeroByteResponsesSeparately() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().eventListenerFactory(BatteryDiagnostics.httpListenerFactory).build()
        server.enqueue(MockResponse().setResponseCode(404).setBody("private error response"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        server.start()
        BatteryDiagnostics.setEnabled(app, true)
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(server.url("/api/chat/files/private-id")).build()
                client.newCall(request).execute().use { assertEquals(404, it.code) }
                client.newCall(request).execute().use { assertEquals("", it.body!!.string()) }
            }
            val text = exportedText()
            assertTrue(text.contains("HTTP.files.status_404=1"))
            assertTrue(text.contains("HTTP.files.status_2xx=1"))
            assertFalse(text.contains("private error response"))
            assertFalse(text.contains("private-id"))
        } finally {
            server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
