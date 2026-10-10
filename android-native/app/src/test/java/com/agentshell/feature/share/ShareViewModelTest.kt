package com.agentshell.feature.share

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.repository.SharedContentRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShareViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var shares: SharedContentRepository
    private lateinit var hosts: HostRepository
    private lateinit var preferencesScope: CoroutineScope
    private val stores = mutableListOf<ViewModelStore>()
    private val client = OkHttpClient()
    // An empty discovery source prevents any test from connecting to embedded production hosts.
    private val hostDao = object : HostDao {
        override fun getAll() = flowOf(emptyList<Host>())
        override suspend fun getById(id: String): Host? = null
        override suspend fun insert(host: Host) = Unit
        override suspend fun delete(host: Host) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "shared_drafts").deleteRecursively()
        shares = SharedContentRepository(app)
        preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = preferencesScope,
            produceFile = { File(directory.root, "share.preferences_pb") }))
        hosts = HostRepository(hostDao, preferences)
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        runBlocking { preferencesScope.coroutineContext[Job]?.cancelAndJoin() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle): ShareViewModel = ShareViewModel(saved, hosts, shares, client).also {
        stores.add(ViewModelStore().apply { put("share", it) })
    }

    @Test fun processRestorationKeepsPendingShareAndConsumptionPreventsReplay() = runBlocking {
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Review this link"))
        val original = withTimeout(5_000) { first.uiState.first { it.draft != null }.draft!! }
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(original, withTimeout(5_000) { restored.uiState.first { it.draft != null }.draft })
        restored.consume()
        assertNull(restored.uiState.value.id)
        // Handing the draft to a chat must retain its content until that chat sends or removes it.
        assertEquals(original, shares.load(original.id))
    }

    @Test fun cancellingRemovesPendingContentAndInvalidShareCanBeReplaced() = runBlocking {
        val saved = SavedStateHandle()
        val model = viewModel(saved)
        model.receive(Intent(Intent.ACTION_SEND))
        assertNotNull(model.uiState.value.error)
        model.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A valid share"))
        val draft = withTimeout(5_000) { model.uiState.first { it.draft != null }.draft!! }
        assertNull(model.uiState.value.error)
        model.consume(discard = true)
        val draftDirectory = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "shared_drafts/${draft.id}")
        withTimeout(5_000) {
            // The discard removes the JSON file concurrently. Check the final
            // directory deletion instead of racing a read with that removal.
            while (draftDirectory.exists()) delay(10)
        }
        assertFalse(saved.contains("incoming_share"))
        assertFalse(saved.contains("share_id"))
    }

    @Test fun anotherShareGetsAnIndependentDraftAndOrdinaryLaunchDoesNotReplaceIt() = runBlocking {
        val model = viewModel(SavedStateHandle())
        model.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "First"))
        val first = withTimeout(5_000) { model.uiState.first { it.draft != null }.draft!! }
        model.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Second"))
        val second = withTimeout(5_000) { model.uiState.first { it.draft?.text == "Second" }.draft!! }
        assertNotEquals(first.id, second.id)
        model.receive(Intent(Intent.ACTION_MAIN))
        assertEquals(second, model.uiState.value.draft)
    }
}
