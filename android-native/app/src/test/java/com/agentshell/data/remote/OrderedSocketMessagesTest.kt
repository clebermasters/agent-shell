package com.agentshell.data.remote

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OrderedSocketMessagesTest {
    @Test fun aSlowConsumerRetainsWireOrderAndBackpressuresInsteadOfDroppingAnAcknowledgement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = CopyOnWriteArrayList<Int>()
        val messages = OrderedSocketMessages<Int>(this, 1) {
            if (it == 0) { entered.complete(Unit); release.await() }
            received.add(it)
        }
        try {
            assertTrue(messages.accept(0)); entered.await()
            assertTrue(messages.accept(1))
            val acknowledgement = async(Dispatchers.IO) { messages.accept(2) }
            delay(50)
            assertFalse("A full queue must apply backpressure", acknowledgement.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { acknowledgement.await() })
            messages.close()
            withTimeout(5_000) { while (received.size < 3) delay(1) }
            assertEquals(listOf(0, 1, 2), received.toList())
        } finally { messages.cancel() }
    }

    @Test fun replacingASocketReleasesItsBlockedReader() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val messages = OrderedSocketMessages<Int>(this, 1) { entered.complete(Unit); wait.await() }
        try {
            messages.accept(0); entered.await(); messages.accept(1)
            val blocked = async(Dispatchers.IO) { messages.accept(2) }
            delay(50); assertFalse(blocked.isCompleted)
            messages.cancel()
            assertFalse(withTimeout(5_000) { blocked.await() })
        } finally { messages.cancel() }
    }
}
