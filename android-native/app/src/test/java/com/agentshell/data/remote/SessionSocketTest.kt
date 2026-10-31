package com.agentshell.data.remote

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class SessionSocketTest {
    private open class TestListener(private val sockets: MutableList<WebSocket>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(webSocket) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets.remove(webSocket) }
    }

    @Test fun aPeerCloseReconnectsThePanelWithoutWaitingForTheNativePingInterval() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = SessionSocket(client)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val opened = AtomicInteger()
        val listener = object : TestListener(sockets) {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                if (opened.incrementAndGet() == 1) webSocket.close(1001, "synthetic restart")
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        try {
            service.connect(server.url("/ws").toString().replaceFirst("http:", "ws:"))
            withTimeout(5_000) { service.connectionGeneration.first { it == 2L } }
            assertTrue(service.isConnected.value)
        } finally {
            service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun reconnectChangesGenerationAndUnsentBoundTransfersAreNotQueuedForTheNewSocket() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = SessionSocket(client)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val received = CopyOnWriteArrayList<String>()
        val listener = object : TestListener(sockets) {
            override fun onMessage(webSocket: WebSocket, text: String) { received.add(text) }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        server.start()
        val url = server.url("/ws").toString().replaceFirst("http:", "ws:")
        try {
            assertFalse(service.sendIfConnected(mapOf("type" to "test-transfer", "id" to "offline"), url))
            service.connect(url)
            withTimeout(5_000) { service.connectionGeneration.first { it == 1L } }
            assertFalse(service.sendIfConnected(mapOf("type" to "test-transfer", "id" to "wrong-server"), "$url/other"))
            assertTrue(service.sendIfConnected(mapOf("type" to "test-transfer", "id" to "once"), url))
            withTimeout(5_000) { while (received.isEmpty()) delay(10) }
            service.forceReconnect()
            assertFalse(service.sendIfConnected(mapOf("type" to "test-transfer", "id" to "during-reconnect"), url))
            withTimeout(5_000) { service.connectionGeneration.first { it == 2L } }
            delay(100)
            assertTrue(service.isConnected.value)
            assertEquals(listOf("once"), received.map { JSONObject(it).getString("id") })
        } finally {
            service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun panelPacketsPreserveBindingHistoryAndAcknowledgementOrder() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = SessionSocket(client)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val received = CopyOnWriteArrayList<Int>()
        val count = 400
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestListener(sockets) {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                repeat(count) { index ->
                    webSocket.send(JSONObject().put("type", "chat-send-result").put("sequence", index).toString())
                }
            }
        }))
        server.start()
        val observer = launch(Dispatchers.Default, start=CoroutineStart.UNDISPATCHED) {
            service.messages.collect { (it["sequence"] as? Number)?.let { index -> received.add(index.toInt()) } }
        }
        try {
            service.connect(server.url("/ws").toString().replaceFirst("http:", "ws:"))
            withTimeout(10_000) { while (received.size < count) delay(10) }
            assertEquals((0 until count).toList(), received.toList())
        } finally {
            observer.cancelAndJoin(); service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
