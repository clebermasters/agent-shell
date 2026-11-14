package com.agentshell.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import com.agentshell.data.model.ChatTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatSplitPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private data class Opened(val raw: DataStore<Preferences>, val prefs: PreferencesDataStore, val scope: CoroutineScope)

    private fun open(): Opened {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val raw = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(temporary.root, "split.preferences_pb") })
        return Opened(raw, PreferencesDataStore(raw), scope)
    }

    @After fun cleanup() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun unseenChatsStartWithoutATerminalAndWithAnAdaptiveLayout() = runBlocking {
        val saved = open().prefs.chatSplitPreferences("host-a", "work", 0).first()
        assertEquals(ChatSplitPreferences(), saved)
        assertFalse(saved.enabled)
        assertEquals(ChatSplitOrientation.AUTO, saved.orientation)
    }

    @Test fun hostSessionAndWindowEachHaveIndependentPreferences() = runBlocking {
        val prefs = open().prefs
        val selected = ChatSplitPreferences(
            enabled = true,
            orientation = ChatSplitOrientation.SIDE_BY_SIDE,
            chatFirst = false,
            stackedFraction = 0.7f,
            sideBySideFraction = 0.35f,
        )
        prefs.setChatSplitPreferences("host-a", "work", 1, selected)
        assertEquals(selected, prefs.chatSplitPreferences("host-a", "work", 1).first())
        for ((host, session, window) in listOf(
            Triple("host-b", "work", 1),
            Triple("host-a", "other", 1),
            Triple("host-a", "work", 0),
        )) {
            assertEquals(ChatSplitPreferences(), prefs.chatSplitPreferences(host, session, window).first())
        }
        // Delimiters and Unicode in session names must not alias another target.
        prefs.setChatSplitPreferences("host-a", "work_|☃", 1, selected)
        assertEquals(selected, prefs.chatSplitPreferences("host-a", "work_|☃", 1).first())
        assertEquals(ChatSplitPreferences(), prefs.chatSplitPreferences("host-a", "work_", 1).first())
    }

    @Test fun allChoicesSurviveClosingAndReopeningDataStore() = runBlocking {
        val original = open()
        val selected = ChatSplitPreferences(
            enabled = true,
            orientation = ChatSplitOrientation.STACKED,
            chatFirst = false,
            stackedFraction = 0.73f,
            sideBySideFraction = 0.42f,
        )
        original.prefs.setChatSplitPreferences("host-a", "work", 2, selected)
        original.scope.coroutineContext[Job]?.cancelAndJoin()
        assertEquals(selected, open().prefs.chatSplitPreferences("host-a", "work", 2).first())
    }

    @Test fun disablingSplitPreservesTheUsersLayoutForTheNextTime() = runBlocking {
        val prefs = open().prefs
        val selected = ChatSplitPreferences(true, ChatSplitOrientation.SIDE_BY_SIDE, false, 0.7f, 0.4f)
        prefs.setChatSplitPreferences("host-a", "work", value = selected)
        prefs.setChatSplitPreferences("host-a", "work", value = selected.copy(enabled = false))
        assertEquals(selected.copy(enabled = false), prefs.chatSplitPreferences("host-a", "work").first())
    }

    @Test fun invalidFractionsCannotCollapseOrBreakAPane() = runBlocking {
        val prefs = open().prefs
        prefs.setChatSplitPreferences("host-a", "work", value = ChatSplitPreferences(
            stackedFraction = Float.NaN, sideBySideFraction = Float.POSITIVE_INFINITY,
        ))
        assertEquals(ChatSplitPreferences(), prefs.chatSplitPreferences("host-a", "work").first())
        prefs.setChatSplitPreferences("host-a", "work", value = ChatSplitPreferences(
            stackedFraction = -3f, sideBySideFraction = 8f,
        ))
        val saved = prefs.chatSplitPreferences("host-a", "work").first()
        assertEquals(ChatSplitPreferences.MIN_FRACTION, saved.stackedFraction, 0f)
        assertEquals(ChatSplitPreferences.MAX_FRACTION, saved.sideBySideFraction, 0f)
    }

    @Test fun brokenStoredLayoutFallsBackAndFutureFieldsAreIgnored() = runBlocking {
        val opened = open()
        val key = stringPreferencesKey("chat_split_${ChatTarget.create("host-a", "work").key}")
        opened.raw.edit { it[key] = "invalid json" }
        assertEquals(ChatSplitPreferences(), opened.prefs.chatSplitPreferences("host-a", "work").first())
        opened.raw.edit { it[key] = """{"enabled":true,"futureField":"value"}""" }
        assertEquals(ChatSplitPreferences(enabled = true), opened.prefs.chatSplitPreferences("host-a", "work").first())
    }

    @Test fun resizingOneOrientationKeepsTheOtherDividerPosition() {
        val selected = ChatSplitPreferences(stackedFraction = 0.7f, sideBySideFraction = 0.4f)
        val changed = selected.withFraction(ChatSplitOrientation.SIDE_BY_SIDE, 0.65f)
        assertEquals(0.7f, changed.fractionFor(ChatSplitOrientation.STACKED), 0f)
        assertEquals(0.65f, changed.fractionFor(ChatSplitOrientation.SIDE_BY_SIDE), 0f)
    }
}
