package com.agentshell.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class DraftMigrationPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val legacyKey = "chat_draft_session_0"
    private val nativeKey = "chat_draft_native_https://server:conversation-a"
    private val otherNativeKey = "chat_draft_native_https://server:conversation-b"
    private data class Opened(val raw: DataStore<Preferences>, val prefs: PreferencesDataStore, val scope: CoroutineScope)

    private fun open(): Opened {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val raw = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(temporary.root, "drafts.preferences_pb") })
        return Opened(raw, PreferencesDataStore(raw), scope)
    }

    @After fun cleanup() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun existingNativeDraftWinsAndTheObservedLegacyValueIsRemoved() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "stale session text")
        prefs.setDraftMessage(nativeKey, "saved native text")
        assertEquals("saved native text", prefs.migrateDraftMessage(legacyKey, nativeKey, true, "stale session text"))
        assertEquals("saved native text", prefs.getDraftMessage(nativeKey))
        assertNull(prefs.getDraftMessage(legacyKey))
    }

    @Test fun explicitEmptyNativeDraftWinsInsteadOfResurrectingLegacyText() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "already sent text")
        prefs.setDraftMessage(nativeKey, "", keepEmpty = true)
        assertEquals("", prefs.migrateDraftMessage(legacyKey, nativeKey, true, "already sent text"))
        assertEquals("", prefs.getDraftMessage(nativeKey))
        assertNull(prefs.getDraftMessage(legacyKey))
    }

    @Test fun eligibleLegacyTextMovesOnceAndTheNativeCopySurvivesProcessReopen() = runBlocking {
        val first = open()
        first.prefs.setDraftMessage(legacyKey, "genuine pre-binding draft")
        assertEquals("genuine pre-binding draft", first.prefs.migrateDraftMessage(legacyKey, nativeKey, true))
        assertNull(first.prefs.getDraftMessage(legacyKey))
        first.scope.coroutineContext[Job]?.cancelAndJoin()
        val reopened = open()
        assertEquals("genuine pre-binding draft", reopened.prefs.getDraftMessage(nativeKey))
        assertEquals("genuine pre-binding draft", reopened.prefs.migrateDraftMessage(legacyKey, nativeKey, true))
        assertNull(reopened.prefs.getDraftMessage(legacyKey))
    }

    @Test fun aKnownNativeIdentityDiscardsOnlyTheObservedStaleLegacyValue() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "old conversation draft")
        assertNull(prefs.migrateDraftMessage(legacyKey, nativeKey, false, "old conversation draft"))
        assertNull(prefs.getDraftMessage(nativeKey))
        assertNull(prefs.getDraftMessage(legacyKey))
    }

    @Test fun unknownOrChangedLegacyTextIsRetainedWhenItIsNotBeingCopied() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "another view's newer text")
        assertNull(prefs.migrateDraftMessage(legacyKey, nativeKey, false))
        assertEquals("another view's newer text", prefs.getDraftMessage(legacyKey))
        assertNull(prefs.migrateDraftMessage(legacyKey, nativeKey, false, "previously loaded text"))
        assertEquals("another view's newer text", prefs.getDraftMessage(legacyKey))
        prefs.setDraftMessage(nativeKey, "canonical value")
        assertEquals("canonical value", prefs.migrateDraftMessage(legacyKey, nativeKey, true, "previously loaded text"))
        assertEquals("another view's newer text", prefs.getDraftMessage(legacyKey))
    }

    @Test fun copyingPreservesTheCurrentLegacyValueEvenIfTheCallersSnapshotWasOlder() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "newer pre-binding draft")
        assertEquals("newer pre-binding draft", prefs.migrateDraftMessage(legacyKey, nativeKey, true, "older snapshot"))
        assertEquals("newer pre-binding draft", prefs.getDraftMessage(nativeKey))
        assertNull(prefs.getDraftMessage(legacyKey))
    }

    @Test fun anExplicitEmptyLegacyValueCanBeCopiedWithoutTurningIntoAMissingDraft() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "", keepEmpty = true)
        assertEquals("", prefs.migrateDraftMessage(legacyKey, nativeKey, true))
        assertEquals("", prefs.getDraftMessage(nativeKey))
        assertNull(prefs.getDraftMessage(legacyKey))
    }

    @Test fun identicalKeysAreNeverRemovedAndMissingDraftsStayMissing() = runBlocking {
        val prefs = open().prefs
        assertNull(prefs.migrateDraftMessage(legacyKey, nativeKey, true))
        assertNull(prefs.getDraftMessage(nativeKey))
        prefs.setDraftMessage(nativeKey, "keep this draft")
        for (eligible in listOf(false, true)) {
            assertEquals("keep this draft", prefs.migrateDraftMessage(nativeKey, nativeKey, eligible, "keep this draft"))
            assertEquals("keep this draft", prefs.getDraftMessage(nativeKey))
        }
        prefs.setDraftMessage(nativeKey, "", keepEmpty = true)
        assertEquals("", prefs.migrateDraftMessage(nativeKey, nativeKey, false, ""))
        assertEquals("", prefs.getDraftMessage(nativeKey))
    }

    @Test fun migrationLeavesUnrelatedDraftsAndPreferencesUntouched() = runBlocking {
        val prefs = open().prefs
        prefs.setThemeMode("dark")
        prefs.setDraftMessage(otherNativeKey, "different native conversation")
        prefs.setDraftMessage("chat_draft_other_session_1", "other legacy session")
        prefs.setDraftMessage(legacyKey, "migrate me")
        prefs.migrateDraftMessage(legacyKey, nativeKey, true)
        assertEquals("dark", prefs.themeMode.first())
        assertEquals("different native conversation", prefs.getDraftMessage(otherNativeKey))
        assertEquals("other legacy session", prefs.getDraftMessage("chat_draft_other_session_1"))
    }

    @Test fun simultaneousBindingsCannotCopyOneLegacyDraftIntoTwoNativeConversations() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(legacyKey, "one genuine draft")
        val results = listOf(nativeKey, otherNativeKey).map { target ->
            async(Dispatchers.Default) { prefs.migrateDraftMessage(legacyKey, target, true) }
        }.awaitAll()
        assertEquals(1, results.count { it == "one genuine draft" })
        assertEquals(1, results.count { it == null })
        assertNull(prefs.getDraftMessage(legacyKey))
        assertEquals(1, listOf(prefs.getDraftMessage(nativeKey), prefs.getDraftMessage(otherNativeKey)).count { it == "one genuine draft" })
    }

    @Test fun aConcurrentWriterCannotHaveItsNewerUncopiedLegacyValueRemoved() = runBlocking {
        val prefs = open().prefs
        prefs.setDraftMessage(nativeKey, "canonical")
        repeat(10) { index ->
            val old = "observed-$index"
            val newer = "newer-$index"
            prefs.setDraftMessage(legacyKey, old)
            awaitAll(
                async(Dispatchers.Default) { prefs.migrateDraftMessage(legacyKey, nativeKey, false, old) },
                async(Dispatchers.Default) { prefs.setDraftMessage(legacyKey, newer) },
            )
            assertEquals(newer, prefs.getDraftMessage(legacyKey))
            assertEquals("canonical", prefs.getDraftMessage(nativeKey))
        }
    }

    @Test fun copyAndLegacyRemovalAreOneCommittedDataStoreTransaction() = runBlocking {
        val opened = open()
        opened.prefs.setDraftMessage(legacyKey, "atomic draft")
        val committed = mutableListOf<Pair<String?, String?>>()
        val observed = object : DataStore<Preferences> {
            override val data = opened.raw.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                return opened.raw.updateData(transform).also {
                    committed += it[stringPreferencesKey(legacyKey)] to it[stringPreferencesKey(nativeKey)]
                }
            }
        }
        assertEquals("atomic draft", PreferencesDataStore(observed).migrateDraftMessage(legacyKey, nativeKey, true))
        assertEquals(listOf(null to "atomic draft"), committed)
    }
}
