package com.agentshell.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.Host
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecentSessionsRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val main = Host("main", "Main", "main.example.test", 443)
    private val lab = Host("lab", "Homelab", "lab.example.test", 443)
    private val selected = MutableStateFlow<Host?>(main)
    private val connection = MutableStateFlow(ConnectionStatus.CONNECTED)
    private val messages = MutableSharedFlow<Map<String, Any?>>()
    private val requests = MutableStateFlow(0)
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var preferences: PreferencesDataStore
    private lateinit var repository: RecentSessionsRepository
    @Volatile private var url = "${main.wsUrl}/ws"

    @Before fun setup() = runBlocking<Unit> {
        preferences = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(directory.root, "history.preferences_pb") }))
        repository = RecentSessionsRepository(preferences, selected, connection, messages,
            { url }, { requests.value += 1 }, scope)
        withTimeout(5000) { requests.first { it > 0 } }
    }

    @After fun cleanup() = runBlocking<Unit> {
        scope.coroutineContext[Job]?.cancelAndJoin()
        dispatcher.close()
    }

    private fun tmux(name: String, window: Int = 0) = "tmux:$name\u0000$window"
    private suspend fun catalog(vararg names: String, host: Host = selected.value!!) {
        messages.emit(mapOf("type" to "sessions-list", "_sourceServerUrl" to "${host.wsUrl}/ws",
            "sessions" to names.map { mapOf("name" to it) }))
    }
    private suspend fun direct(vararg ids: String) {
        messages.emit(mapOf("type" to "acp-sessions-listed", "_sourceServerUrl" to url,
            "sessions" to ids.map { mapOf("sessionId" to it) }))
    }
    private suspend fun awaitTerminal(expected: List<String>) = withTimeout(5000) {
        repository.terminal.first { it == expected }
    }
    private suspend fun awaitChat(expected: List<String>) = withTimeout(5000) {
        repository.chat.first { it == expected }
    }

    @Test fun deletingSessionRemovesAllItsChatWindowsAndTerminalHistoryDurably() = runBlocking<Unit> {
        catalog("deleted", "kept")
        repository.visitTerminal("kept"); repository.visitTerminal("deleted")
        repository.visitChat(tmux("kept")); repository.visitChat(tmux("deleted", 0)); repository.visitChat(tmux("deleted", 2))
        awaitTerminal(listOf("deleted", "kept"))
        awaitChat(listOf(tmux("deleted", 2), tmux("deleted", 0), tmux("kept")))
        catalog("kept")
        awaitTerminal(listOf("kept")); awaitChat(listOf(tmux("kept")))
        withTimeout(5000) { preferences.recentTerminalSessions(main.id).first { it == listOf("kept") } }
        withTimeout(5000) { preferences.recentChatSessions(main.id).first { it == listOf(tmux("kept")) } }
    }

    @Test fun emptyTmuxCatalogClearsTmuxHistoryWithoutDiscardingDirectSessions() = runBlocking<Unit> {
        catalog("terminal"); direct("direct")
        repository.visitTerminal("terminal")
        repository.visitChat(tmux("terminal")); repository.visitChat("acp:direct")
        awaitChat(listOf("acp:direct", tmux("terminal")))
        catalog()
        awaitTerminal(emptyList()); awaitChat(listOf("acp:direct"))
        direct()
        awaitChat(emptyList())
        withTimeout(5000) { preferences.recentChatSessions(main.id).first { it.isEmpty() } }
    }

    @Test fun directDeletionEventImmediatelyRemovesItFromSwipeHistory() = runBlocking<Unit> {
        direct("first", "second")
        repository.visitChat("acp:first"); repository.visitChat("acp:second")
        awaitChat(listOf("acp:second", "acp:first"))
        messages.emit(mapOf("type" to "acp-session-deleted", "sessionId" to "second", "_sourceServerUrl" to url))
        awaitChat(listOf("acp:first"))
        withTimeout(5000) { preferences.recentChatSessions(main.id).first { it == listOf("acp:first") } }
    }

    @Test fun serverSwitchKeepsSeparateHistoriesAndIgnoresLatePreviousServerPackets() = runBlocking<Unit> {
        catalog("main-only", "shared")
        repository.visitTerminal("main-only"); repository.visitTerminal("shared")
        awaitTerminal(listOf("shared", "main-only"))
        var previousRequests = requests.value
        withContext(dispatcher) { url = "${lab.wsUrl}/ws"; selected.value = lab }
        withTimeout(5000) { requests.first { it > previousRequests } }
        awaitTerminal(emptyList())
        catalog("lab-only", "shared")
        repository.visitTerminal("lab-only")
        awaitTerminal(listOf("lab-only"))
        catalog(host = main)
        assertEquals(listOf("lab-only"), repository.terminal.value)
        previousRequests = requests.value
        withContext(dispatcher) { url = "${main.wsUrl}/ws"; selected.value = main }
        withTimeout(5000) { requests.first { it > previousRequests } }
        awaitTerminal(emptyList())
        catalog("main-only", "shared")
        awaitTerminal(listOf("shared", "main-only"))
    }

    @Test fun reconnectHidesStaleEntriesUntilAuthoritativeSnapshotAndPrunesOfflineDeletions() = runBlocking<Unit> {
        catalog("deleted-while-offline")
        repository.visitTerminal("deleted-while-offline")
        awaitTerminal(listOf("deleted-while-offline"))
        connection.value = ConnectionStatus.RECONNECTING
        awaitTerminal(emptyList())
        val previousRequests = requests.value
        connection.value = ConnectionStatus.CONNECTED
        withTimeout(5000) { requests.first { it > previousRequests } }
        assertTrue(repository.terminal.value.isEmpty())
        catalog()
        withTimeout(5000) { preferences.recentTerminalSessions(main.id).first { it.isEmpty() } }
        assertTrue(repository.terminal.value.isEmpty())
    }

    @Test fun visitsKeepThreeMostRecentDistinctEntriesInTheirOriginalOrder() = runBlocking<Unit> {
        catalog("one", "two", "three", "four")
        for (name in listOf("one", "two", "three", "four", "two")) repository.visitTerminal(name)
        awaitTerminal(listOf("two", "four", "three"))
        catalog("two", "three")
        awaitTerminal(listOf("two", "three"))
    }
}
