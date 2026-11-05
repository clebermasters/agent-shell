package com.agentshell.data.remote

import android.app.Application
import com.agentshell.core.diagnostics.DiagnosticSocketRole
import com.agentshell.data.model.ConnectionStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TransportSuspensionTest {
    /** Models a false already captured by a collector before a newer true decision. */
    @OptIn(InternalCoroutinesApi::class)
    private class CapturedGate(private val current: MutableStateFlow<Boolean> = MutableStateFlow(true)) : StateFlow<Boolean> by current {
        private val captured = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1).also { it.tryEmit(true) }
        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing = captured.collect(collector)
        fun delayedFalseAfterRecovery() {
            current.value = false
            check(captured.tryEmit(false))
            current.value = true
        }
    }
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val gate = MutableStateFlow(true)
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val primary = mutableListOf<WebSocketService>()
    private val session = mutableListOf<SessionSocket>()
    private val peerSockets = CopyOnWriteArrayList<WebSocket>()
    private val packets = CopyOnWriteArrayList<String>()
    private val opened = AtomicInteger()
    @Volatile private var headersDelayMillis = 0L
    @Volatile private var firstOpen: ((WebSocket) -> Unit)? = null

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        peerSockets.add(webSocket)
                        if (opened.incrementAndGet() == 1) firstOpen?.invoke(webSocket)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        packets.add(text)
                        if (JSONObject(text).optString("type") == "ping") webSocket.send("{\"type\":\"pong\"}")
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { peerSockets.remove(webSocket) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { peerSockets.remove(webSocket) }
                })
                if (headersDelayMillis > 0) response.setHeadersDelay(headersDelayMillis, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
    }

    @After fun cleanup() = runBlocking {
        primary.forEach { it.dispose() }; session.forEach { it.dispose() }
        scheduler.runCurrent()
        server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        Unit
    }

    private fun primary() = WebSocketService(client, gate, dispatcher).also(primary::add)
    private fun session() = SessionSocket(client, DiagnosticSocketRole.SESSION, gate, dispatcher).also(session::add)
    private fun url(path: String = "/ws") = server.url(path).toString().replaceFirst("http:", "ws:")
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
    private fun noHandshake() { assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS)) }
    private fun passTime() { scheduler.advanceTimeBy(180_000); scheduler.runCurrent() }
    private suspend fun marker(send: () -> Unit) {
        send()
        await { packets.any { JSONObject(it).optString("type") == "visible-marker" } }
        assertFalse(packets.any { it.contains("must-not-replay") })
    }

    @Test fun primarySuspensionLatchesAcrossGateAndAutomaticCallbacksUntilAuthorizedResume() = runBlocking {
        val service = primary()
        service.connect(url())
        await { service.isConnected }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        service.suspendForBackground("timeout")
        assertTrue(service.connectionDesired.value)
        assertFalse(service.keepAliveEnabled.value)
        assertEquals(ConnectionStatus.OFFLINE, service.connectionStatus.value)
        gate.value = false; scheduler.runCurrent()
        service.send(mapOf("type" to "input", "data" to "must-not-replay"))
        service.forceReconnect(); service.checkConnection(); service.onNetworkAvailable()
        assertFalse(service.sendIfConnected(mapOf("type" to "input", "data" to "must-not-replay"), url()))
        gate.value = true; scheduler.runCurrent()
        service.connect(url()) // A host-observer refresh is not permission to resume.
        service.checkConnection(); service.forceReconnect(); service.onNetworkAvailable()
        passTime(); noHandshake()
        assertEquals(1L, service.connectionGeneration.value)
        service.resumeAfterPolicyPause(); service.resumeAfterPolicyPause()
        await { service.isConnected && service.connectionGeneration.value == 2L }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); noHandshake()
        marker { service.send(mapOf("type" to "visible-marker")) }
    }

    @Test fun primaryManualDisconnectStaysOffAndAlsoClearsPacketsQueuedDuringHandshake() = runBlocking {
        headersDelayMillis = 1_000
        val service = primary()
        service.connect(url())
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        service.send(mapOf("type" to "input", "data" to "must-not-replay"))
        service.disconnect()
        assertFalse(service.connectionDesired.value)
        service.checkConnection(); service.forceReconnect(); service.onNetworkAvailable(); service.resumeFromForeground()
        passTime(); noHandshake()
        assertEquals(0L, service.connectionGeneration.value)
        headersDelayMillis = 0
        service.connect(url())
        await { service.isConnected }
        assertEquals(1L, service.connectionGeneration.value)
        marker { service.send(mapOf("type" to "visible-marker")) }
    }

    @Test fun primaryBlockedConnectTracksLatestHostAndAuthWithoutReplayingOlderMutations() = runBlocking {
        gate.value = false
        val service = primary()
        service.connect(url("/old"), "old-auth")
        service.send(mapOf("type" to "input", "data" to "must-not-replay"))
        service.connect(url("/latest"), "new-auth")
        service.resumeAfterPolicyPause()
        passTime(); noHandshake()
        gate.value = true; scheduler.runCurrent(); noHandshake()
        service.resumeAfterPolicyPause()
        await { service.isConnected }
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/latest", request.requestUrl!!.encodedPath)
        assertEquals("new-auth", request.requestUrl!!.queryParameter("token"))
        marker { service.send(mapOf("type" to "visible-marker")) }
    }

    @Test fun primarySuspensionCancelsAnAlreadyScheduledPeerCloseRetry() = runBlocking {
        val service = primary()
        service.connect(url())
        await { service.isConnected }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        peerSockets.first().close(1001, "synthetic peer restart")
        await { service.connectionStatus.value == ConnectionStatus.RECONNECTING }
        scheduler.runCurrent()
        service.suspendForBackground()
        passTime(); noHandshake()
        assertEquals(1L, service.connectionGeneration.value)
        service.resumeAfterPolicyPause()
        await { service.connectionGeneration.value == 2L && service.isConnected }
    }

    @Test fun primarySuspensionCancelsTheServerAnnouncedReconnectDelay() = runBlocking {
        firstOpen = { it.send("{\"type\":\"server-shutting-down\",\"reconnectDelayMs\":120000}") }
        val service = primary()
        service.connect(url())
        await { service.connectionStatus.value == ConnectionStatus.RECONNECTING }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        scheduler.runCurrent()
        service.suspendForBackground()
        passTime(); noHandshake()
        assertEquals(1L, service.connectionGeneration.value)
        service.resumeAfterPolicyPause()
        await { service.isConnected && service.connectionGeneration.value == 2L }
    }

    @Test fun primarySuspensionDuringHandshakeRejectsLateOpenAndDropsItsQueue() = runBlocking {
        headersDelayMillis = 1_000
        val service = primary()
        service.connect(url())
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        service.send(mapOf("type" to "input", "data" to "must-not-replay"))
        service.suspendForBackground()
        delay(1_100)
        assertFalse(service.isConnected)
        assertEquals(0L, service.connectionGeneration.value)
        passTime(); noHandshake()
        headersDelayMillis = 0
        service.resumeAfterPolicyPause()
        await { service.isConnected && service.connectionGeneration.value == 1L }
        marker { service.send(mapOf("type" to "visible-marker")) }
    }

    @Test fun independentSessionGateStopsRetryAndResumesOnceWithoutQueuedMutations() = runBlocking {
        val socket = session()
        socket.connect(url())
        await { socket.isConnected.value }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        peerSockets.first().close(1001, "synthetic peer restart")
        await { !socket.isConnected.value }
        scheduler.runCurrent()
        gate.value = false; scheduler.runCurrent()
        socket.send(mapOf("type" to "input", "data" to "must-not-replay"))
        socket.checkConnection(); socket.forceReconnect()
        passTime(); noHandshake()
        assertFalse(socket.sendIfConnected(mapOf("type" to "input", "data" to "must-not-replay"), url()))
        gate.value = true; scheduler.runCurrent()
        socket.connect(url()) // Repeated desired-host refresh does not open a second socket.
        await { socket.isConnected.value && socket.connectionGeneration.value == 2L }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); noHandshake()
        marker { socket.send(mapOf("type" to "visible-marker")) }
        socket.dispose()
        gate.value = false; scheduler.runCurrent(); gate.value = true; scheduler.runCurrent()
        passTime(); noHandshake()
    }

    @Test fun sessionHandshakePauseCancelsLateSocketAndRemembersOnlyLatestUrl() = runBlocking {
        headersDelayMillis = 1_000
        val socket = session()
        socket.connect(url("/old"))
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        socket.send(mapOf("type" to "input", "data" to "must-not-replay"))
        gate.value = false; scheduler.runCurrent()
        socket.connect(url("/latest"))
        delay(1_100)
        assertEquals(0L, socket.connectionGeneration.value)
        assertFalse(socket.isConnected.value)
        passTime(); noHandshake()
        headersDelayMillis = 0
        gate.value = true; scheduler.runCurrent()
        await { socket.isConnected.value }
        assertEquals("/latest", server.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
        marker { socket.send(mapOf("type" to "visible-marker")) }
    }

    @Test fun staleFalseEmissionCannotSuspendALaterAuthorizedLiveDecision() = runBlocking {
        val delayedGate = CapturedGate()
        val service = WebSocketService(client, delayedGate, dispatcher).also(primary::add)
        val socket = SessionSocket(client, DiagnosticSocketRole.SESSION, delayedGate, dispatcher).also(session::add)
        service.connect(url("/primary")); socket.connect(url("/session"))
        await { service.isConnected && socket.isConnected.value }
        repeat(2) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        delayedGate.delayedFalseAfterRecovery()
        service.resumeAfterPolicyPause()
        scheduler.runCurrent()
        assertTrue(service.isConnected)
        assertTrue(service.keepAliveEnabled.value)
        assertTrue(socket.isConnected.value)
        assertEquals(1L, service.connectionGeneration.value)
        assertEquals(1L, socket.connectionGeneration.value)
        noHandshake()
    }

    @Test fun repeatedAllowedPolicyEvaluationsCannotBypassAnAnnouncedServerDelay() = runBlocking {
        firstOpen = { it.send("{\"type\":\"server-shutting-down\",\"reconnectDelayMs\":120000}") }
        val service = primary()
        // The coordinator reevaluates on each primary status update. Permission
        // reconciliation must not turn those updates into forced reconnects.
        val policy = launch(Dispatchers.Default) {
            service.connectionStatus.collect { service.resumeAfterPolicyPause() }
        }
        try {
            service.connect(url())
            await { service.connectionStatus.value == ConnectionStatus.RECONNECTING }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            scheduler.runCurrent()
            noHandshake()
            scheduler.advanceTimeBy(119_999); scheduler.runCurrent(); noHandshake()
            scheduler.advanceTimeBy(1); scheduler.runCurrent()
            await { service.isConnected && service.connectionGeneration.value == 2L }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); noHandshake()
        } finally { policy.cancelAndJoin() }
    }

    @Test fun repeatedForegroundHealthChecksCannotReplaceAnInProgressHandshake() = runBlocking {
        headersDelayMillis = 800
        val service = primary()
        service.connect(url())
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        repeat(20) { service.checkConnection(); service.resumeAfterPolicyPause() }
        await { service.isConnected }
        assertEquals(1L, service.connectionGeneration.value)
        noHandshake()
    }
}
