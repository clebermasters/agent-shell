package com.agentshell.feature.splitscreen

import com.agentshell.feature.chat.ChatDraftWriter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SplitChatDraftTest {
    @Test fun aCachedNativeIdentityNeverCopiesAnOldFallbackEvenWithoutAMatchingLatestReceipt() {
        val migration = panelDraftMigration(cachedNativeIdentity = true, firstNativeBinding = true,
            deliveredLegacy = false, localFallbackEdit = false)
        assertFalse(migration.allowLegacy)
        assertFalse(migration.saveLocalEdit)
    }

    @Test fun onlyAnUncachedFirstBindingMigratesLegacyWhileNewLocalEditsRemainExplicit() {
        assertTrue(panelDraftMigration(false, true, false, false).allowLegacy)
        assertFalse(panelDraftMigration(false, false, false, true).allowLegacy)
        assertFalse(panelDraftMigration(false, false, false, true).saveLocalEdit)
        val newlyTyped = panelDraftMigration(true, true, true, true)
        assertFalse(newlyTyped.allowLegacy)
        assertTrue(newlyTyped.saveLocalEdit)
    }

    @Test fun acceptedAcpOrLegacySendPersistsEmptyEvenIfThePanelImmediatelyLeaves() = runBlocking {
        for (type in listOf("acp-send-prompt", "send-chat-message")) {
            val stored = AtomicReference("old typing")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer = ChatDraftWriter({ _, text ->
                if (text == "old typing") { entered.complete(Unit); release.await() }
                stored.set(text)
            })
            writer.save("$type:conversation", "old typing")
            withTimeout(5_000) { entered.await() }
            writer.save("$type:conversation", "sent prompt")
            var sends = 0
            assertTrue(sendPanelDraft("$type:conversation", writer) { sends++; true })
            writer.close()
            release.complete(Unit)
            withTimeout(5_000) { writer.flush() }
            assertEquals(1, sends)
            assertEquals("", stored.get())
        }
    }

    @Test fun rejectedSocketSendPreservesTheUnsentDraft() = runBlocking {
        val stored = AtomicReference("")
        val writer = ChatDraftWriter({ _, text -> stored.set(text) })
        writer.save("draft", "unsent prompt")
        assertFalse(sendPanelDraft("draft", writer) { false })
        writer.close()
        withTimeout(5_000) { writer.flush() }
        assertEquals("unsent prompt", stored.get())
        assertEquals("unsent prompt", writer.snapshot("draft")!!.text)
    }

    @Test fun aNewEditAfterAcceptedSendSurvivesInsteadOfBeingClearedByTheOldPrompt() = runBlocking {
        val stored = AtomicReference("")
        val writer = ChatDraftWriter({ _, text -> stored.set(text) })
        writer.save("draft", "sent prompt")
        assertTrue(sendPanelDraft("draft", writer) { true })
        writer.save("draft", "new unsent draft")
        writer.close()
        withTimeout(5_000) { writer.flush() }
        assertEquals("new unsent draft", stored.get())
        assertEquals("new unsent draft", writer.restore("draft") { "sent prompt" })
    }
}
