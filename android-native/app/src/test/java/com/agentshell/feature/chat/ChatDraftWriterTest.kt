package com.agentshell.feature.chat

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChatDraftWriterTest {
    @Test fun editMetadataSurvivesImmediateCloseEvenWhenIdenticalDraftWritesCoalesce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<String>()
        val writer = ChatDraftWriter({ _, text ->
            if (text == "blocked") { entered.complete(Unit); release.await() }
            writes.add(text)
        })
        writer.save("draft", "blocked")
        withTimeout(5_000) { entered.await() }
        writer.save("draft", "same prompt")
        writer.afterWrites { writes.add("edited-marker") }
        writer.save("draft", "same prompt")
        writer.close()
        release.complete(Unit)
        withTimeout(5_000) { writer.flush() }
        assertEquals(listOf("blocked", "edited-marker", "same prompt"), writes.toList())
    }

    @Test fun acceptedSendClearSurvivesImmediateCloseAndOlderTypingCannotFollowIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<String>()
        val writer = ChatDraftWriter({ _, text ->
            if (text == "old typing") { entered.complete(Unit); release.await() }
            writes.add(text)
        })
        writer.save("draft", "old typing")
        withTimeout(5_000) { entered.await() }
        writer.save("draft", "sent prompt")
        writer.save("draft", "")
        writer.close()
        release.complete(Unit)
        withTimeout(5_000) { writer.flush() }
        assertEquals(listOf("old typing", ""), writes.toList())
        assertEquals("", writer.snapshot("draft")!!.text)
    }

    @Test fun aDelayedReadCannotReplaceAnEditOrAcceptedEmptyTombstone() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = ChatDraftWriter({ _, _ -> })
        try {
            val restoring = async(Dispatchers.IO) {
                writer.restore("draft") { entered.complete(Unit); release.await(); "already sent" }
            }
            withTimeout(5_000) { entered.await() }
            writer.save("draft", "")
            release.complete(Unit)
            assertEquals("", withTimeout(5_000) { restoring.await() })
            writer.save("draft", "new unsent draft")
            assertEquals("new unsent draft", writer.restore("draft") { error("local intent takes priority") })
        } finally { writer.close(); writer.flush() }
    }

    @Test fun aNewViewsEditWinsOverTheOldViewsAcceptedClear() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<String>()
        val old = ChatDraftWriter({ _, text ->
            if (text == "sending") { entered.complete(Unit); release.await() }
            writes.add(text)
        })
        val next = ChatDraftWriter({ _, text -> writes.add(text) })
        old.save("draft", "sending")
        withTimeout(5_000) { entered.await() }
        old.save("draft", "")
        old.close()
        next.save("draft", "new unsent draft")
        release.complete(Unit)
        withTimeout(5_000) { next.flush() }
        assertEquals(listOf("sending", "", "new unsent draft"), writes.toList())
        next.close()
    }

    @Test fun typingBurstsCoalesceAndFlushIsAStableMigrationBarrier() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<String>()
        val writer = ChatDraftWriter({ _, text ->
            if (text == "first") { entered.complete(Unit); release.await() }
            writes.add(text)
        })
        writer.save("legacy", "first")
        withTimeout(5_000) { entered.await() }
        repeat(300) { writer.save("legacy", "typed-$it") }
        release.complete(Unit)
        withTimeout(5_000) { writer.flush() }
        assertEquals(listOf("first", "typed-299"), writes.toList())
        writer.close()
    }
}
