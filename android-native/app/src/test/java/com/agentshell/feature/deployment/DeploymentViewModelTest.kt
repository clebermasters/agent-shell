package com.agentshell.feature.deployment

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.DeploymentPreferences
import com.agentshell.data.model.Host
import com.agentshell.data.repository.HostRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeploymentViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: PreferencesDataStore
    private val dao = object : HostDao {
        private val hosts = listOf(Host("a", "A", "a.test", 443), Host("b", "B", "b.test", 443))
        override fun getAll() = flowOf(hosts)
        override suspend fun getById(id: String) = hosts.find { it.id == id }
        override suspend fun insert(host: Host) = Unit
        override suspend fun delete(host: Host) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(temporary.root, "deployment.preferences_pb")
        })
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private fun viewModel(): DeploymentViewModel =
        DeploymentViewModel(HostRepository(dao, prefs), prefs).also {
            stores += ViewModelStore().apply { put("deployment", it) }
        }

    private suspend fun DeploymentViewModel.awaitState(predicate: (DeploymentPreferences) -> Boolean): DeploymentPreferences =
        withTimeout(5_000) { state.first { it != null && predicate(it) }!! }

    @Test fun rapidEditsFromBothDestinationsSurviveNavigationAndRestart() = runBlocking {
        val chat = viewModel()
        val browser = viewModel()
        chat.awaitState { it.hostId == "a" }
        browser.awaitState { it.hostId == "a" }
        val target = ChatTarget.create("a", "release", 4)
        chat.openChat(target)
        browser.setOrientation(ChatSplitOrientation.SIDE_BY_SIDE)
        chat.resize(ChatSplitOrientation.STACKED, 0.66f)
        browser.resize(ChatSplitOrientation.SIDE_BY_SIDE, 0.41f)
        chat.swapPanes()
        stores.forEach { it.clear() }
        val layout = ChatSplitPreferences(true, ChatSplitOrientation.SIDE_BY_SIDE, false, 0.66f, 0.41f)
        val expected = DeploymentPreferences("a", target, layout)
        withTimeout(5_000) { prefs.deploymentPreferences("a").first { it == expected } }
        scope.coroutineContext[Job]?.cancelAndJoin()
        openStore()
        assertEquals(expected, viewModel().awaitState { it.hostId == "a" })
        assertEquals(ChatSplitPreferences(), prefs.chatSplitPreferences("a", "release", 4).first())
    }

    @Test fun hostChangeRejectsOldPickerAndRestoresEachHostsOwnChat() = runBlocking {
        val vm = viewModel()
        vm.awaitState { it.hostId == "a" }
        val old = ChatTarget.create("a", "same", 1)
        vm.openChat(old)
        vm.awaitState { it.chat == old }
        prefs.setSelectedHostId("b")
        assertEquals(DeploymentPreferences("b"), vm.awaitState { it.hostId == "b" })
        vm.openChat(old) // The picker from host A just finished dismissing.
        vm.openChat("acp_same", 7, isAcp = true, cwd = "/releases")
        val next = ChatTarget.create("b", "same", 0, isAcp = true, cwd = "/releases")
        assertTrue(vm.awaitState { it.chat == next }.layout.enabled)
        vm.closeSplit()
        assertEquals(next, vm.awaitState { !it.layout.enabled }.chat)
        prefs.setSelectedHostId("a")
        val restored = vm.awaitState { it.hostId == "a" }
        assertEquals(old, restored.chat)
        assertTrue(restored.layout.enabled)
        val last = prefs.deploymentPreferences("b").first()
        assertFalse(last.layout.enabled)
        assertEquals(next, last.chat)
    }
}
