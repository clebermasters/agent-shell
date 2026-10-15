package com.agentshell.data.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HostRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var repository: HostRepository
    private lateinit var preferences: PreferencesDataStore
    private val main = Host("default-main", "Main", "example.test", 443)
    private val lab = Host("default-lab", "Lab", "192.168.0.38", 4010)
    private val lan = Host("default-lan", "Local LAN", "192.168.0.76", 4010)

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferencesDataStore(PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { File(directory.root, "hosts.preferences_pb") }))
        repository = HostRepository(database.hostDao(), preferences)
    }

    @After fun cleanup() {
        database.close()
        runBlocking { storeScope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun upgradeAddsLanOnceAndKeepsExistingIdsNamesOrderAndSelection() = runTest {
        val customizedMain = main.copy(id = "existing-main", name = "My main", sortOrder = 4)
        val existingLab = lab.copy(id = "existing-lab", sortOrder = 8, lastConnected = 123)
        val custom = Host("custom", "Custom", "custom.test", 4010, sortOrder = 12)
        listOf(customizedMain, existingLab, custom).forEach { repository.saveHost(it) }
        preferences.setSelectedHostId(existingLab.id)
        repository.importDefaultHosts(listOf(main, lab, lan))
        repository.importDefaultHosts(listOf(main.copy(id = "different-generated-id"), lab, lan))
        assertEquals(listOf(customizedMain, existingLab, custom, lan.copy(sortOrder = 13)), repository.getHosts().first())
        assertEquals(existingLab.id, repository.getSelectedHostOnce()?.id)
    }

    @Test fun deletedAndEditedDefaultsStayDeletedOrEditedOnFutureStarts() = runTest {
        repository.importDefaultHosts(listOf(main, lab, lan))
        repository.deleteHost(lan.id)
        repository.saveHost(lab.copy(address = "custom-lab.test"))
        repository.importDefaultHosts(listOf(main, lab, lan))
        assertEquals(setOf("example.test", "custom-lab.test"), repository.getHosts().first().map { it.address }.toSet())
    }

    @Test fun concurrentStartupImportsOneCopyAndSelectsFirstDefault() = runTest {
        List(4) { async { repository.importDefaultHosts(listOf(main, lab, lan, lan.copy(id = "duplicate"))) } }.awaitAll()
        assertEquals(3, repository.getHosts().first().size)
        assertEquals(main.id, repository.getSelectedHostOnce()?.id)
    }

    @Test fun endpointMatchingIgnoresHostnameCaseButKeepsDifferentTransports() = runTest {
        repository.saveHost(main.copy(id = "custom-main", address = "EXAMPLE.TEST"))
        repository.saveHost(lan.copy(id = "secure-lan", tls = true))
        repository.importDefaultHosts(listOf(main, lan))
        assertEquals(3, repository.getHosts().first().size)
        assertEquals(2, repository.getHosts().first().count { it.address == lan.address })
    }

    @Test fun upgradeMovesAnUnchangedLanDefaultToHttpsWithoutLosingItsIdentity() = runTest {
        repository.importDefaultHosts(listOf(main, lab, lan))
        preferences.setSelectedHostId(lan.id)
        val secure = lan.copy(id="new-default-id",address="lan.example.test",port=4443,tls=true)
        repository.importDefaultHosts(listOf(main, lab, secure))
        val selected=repository.getSelectedHostOnce()!!
        assertEquals(3,repository.getHosts().first().size)
        assertEquals(lan.id,selected.id)
        assertEquals("https://lan.example.test:4443",selected.httpUrl)
    }

    @Test fun upgradeDoesNotRewriteAUserEditedLanEndpoint() = runTest {
        repository.importDefaultHosts(listOf(main, lab, lan))
        repository.saveHost(lan.copy(address="192.168.0.99"))
        repository.importDefaultHosts(listOf(main,lab,lan.copy(id="secure",address="lan.example.test",port=4443,tls=true)))
        assertEquals(4,repository.getHosts().first().size)
        assertEquals("192.168.0.99",database.hostDao().getById(lan.id)!!.address)
    }

    @Test fun bundledDefaultsAcceptExplicitHttpsOnNonstandardPorts() {
        val parsed=repository.parseBuildTimeHosts("example.test:443,Public|https://lan.example.test:4443,LAN|http://192.168.0.76:4010,HTTP")
        assertEquals(listOf("https://example.test","https://lan.example.test:4443","http://192.168.0.76:4010"),parsed.map { it.httpUrl })
        assertTrue(repository.parseBuildTimeHosts("https://user:pass@host:4443,Bad|https://host:4443/path,Bad|ftp://host:21,Bad").isEmpty())
    }
}
