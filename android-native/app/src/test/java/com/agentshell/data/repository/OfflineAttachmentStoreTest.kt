package com.agentshell.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.model.ChatBlock
import com.agentshell.data.model.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineAttachmentStoreTest {
    @Test fun onlyTheDownloadedUrlUpdatesAndLaterObserversReadSavedBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val server = MockWebServer()
        val client = OkHttpClient()
        val store = OfflineAttachmentStore(context, client)
        server.enqueue(MockResponse().setBody("image-a"))
        server.enqueue(MockResponse().setBody("image-b"))
        server.start()
        val base = server.url("/api/chat/files").toString().trimEnd('/')
        val firstUrl = "$base/a"
        val secondUrl = "$base/b"
        val emissions = CopyOnWriteArrayList<String>()
        val observer = launch(Dispatchers.Default) {
            OfflineAttachmentStore.observeUrl(context, firstUrl).collect { emissions.add(it) }
        }
        fun message(id: String) = ChatMessage(id, "user", null, 1, blocks = listOf(ChatBlock("image", id = id)))
        try {
            withTimeout(5_000) { while (emissions.isEmpty()) delay(10) }
            assertEquals(listOf(firstUrl), emissions.toList())
            store.enqueue(base, listOf(message("a")))
            withTimeout(5_000) { while (emissions.size < 2) delay(10) }
            assertTrue(emissions.last().startsWith("file:"))

            store.enqueue(base, listOf(message("b")))
            withTimeout(5_000) { while (OfflineAttachmentStore.cachedFile(context, secondUrl) == null) delay(10) }
            delay(100)
            assertEquals("An unrelated download must not rebuild this image", 2, emissions.size)
            val later = withTimeout(5_000) { OfflineAttachmentStore.observeUrl(context, firstUrl).first() }
            assertEquals(emissions.last(), later)
            assertEquals("image-a", OfflineAttachmentStore.cachedFile(context, firstUrl)!!.readText())
        } finally {
            observer.cancelAndJoin()
            store.close()
            server.close()
            OfflineAttachmentStore.cachedFile(context, firstUrl)?.delete()
            OfflineAttachmentStore.cachedFile(context, secondUrl)?.delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
