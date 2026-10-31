package com.agentshell.data.remote

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
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
class WebSocketServiceTest {
    private open class TestListener(private val sockets: MutableList<WebSocket>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(webSocket) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets.remove(webSocket) }
    }

    @Test fun aPeerCloseCompletesAndReconnectsWithoutWaitingForTheHeartbeat() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = WebSocketService(client)
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
            withTimeout(8_000) { service.connectionGeneration.first { it == 2L } }
            assertTrue(service.isConnected)
        } finally {
            service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun acknowledgingAPeerCloseKeepsTheServersAnnouncedReconnectDelay() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = WebSocketService(client)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val opened = AtomicInteger()
        val listener = object : TestListener(sockets) {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                if (opened.incrementAndGet() == 1) {
                    webSocket.send("{\"type\":\"server-shutting-down\",\"reconnectDelayMs\":150}")
                    webSocket.close(1001, "synthetic restart")
                }
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        try {
            service.connect(server.url("/ws").toString().replaceFirst("http:", "ws:"))
            withTimeout(2_000) { service.connectionGeneration.first { it == 2L } }
            assertTrue(service.isConnected)
        } finally {
            service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun eachSuccessfulConnectionChangesGenerationEvenWhenTheFinalStatusIsStillConnected() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = WebSocketService(client)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val listener = TestListener(sockets)
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        try {
            service.connect(server.url("/ws").toString().replaceFirst("http:", "ws:"))
            withTimeout(5_000) { service.connectionGeneration.first { it == 1L } }
            assertTrue(service.isConnected)
            service.forceReconnect()
            withTimeout(5_000) { service.connectionGeneration.first { it == 2L } }
            assertEquals(com.agentshell.data.model.ConnectionStatus.CONNECTED, service.connectionStatus.value)
            assertTrue(service.isConnected)
        } finally {
            service.dispose()
            withContext(NonCancellable) { withTimeout(5_000) { while (sockets.isNotEmpty()) delay(10) } }
            server.close()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun aBurstKeepsBindingHistoryAndAcknowledgementPacketsInWireOrder() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val service = WebSocketService(client)
        val received = CopyOnWriteArrayList<Int>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val count = 600
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestListener(sockets) {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                repeat(count) { index ->
                    val type = listOf("chat-binding", "chat-history", "chat-send-result")[index % 3]
                    webSocket.send(JSONObject().put("type", type).put("sequence", index).toString())
                }
            }
        }))
        server.start()
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            service.messages.collect { message -> (message["sequence"] as? Number)?.let { received.add(it.toInt()) } }
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
