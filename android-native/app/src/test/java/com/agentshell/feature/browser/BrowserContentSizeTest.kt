package com.agentshell.feature.browser

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.BrowserContentSize
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class BrowserContentSizeTest {
    @get:Rule val directory=TemporaryFolder()

    @Test fun missingAndUnknownStoredValuesKeepTheExistingDefaultFit() {
        for (value in listOf(null, 0, -1, 110, Int.MAX_VALUE))
            assertEquals(BrowserContentSize.DEFAULT, BrowserContentSize.fromDensityPercent(value))
    }

    @Test fun selectionsSurviveReopeningTheStoreAndStayIndependentAcrossServers()=runBlocking<Unit> {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val file=File(directory.root,"browser.preferences_pb")
        var scope=CoroutineScope(SupervisorJob()+dispatcher)
        try {
            val raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            val store=PreferencesDataStore(raw)
            assertEquals(BrowserContentSize.DEFAULT,store.browserContentSize("main").first())
            store.setBrowserContentSize("main",BrowserContentSize.MORE)
            store.setBrowserContentSize("homelab",BrowserContentSize.LARGER)
            raw.edit { it[intPreferencesKey("browser_content_density_old")]=999 }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope=CoroutineScope(SupervisorJob()+dispatcher)
            val reopened=PreferencesDataStore(PreferenceDataStoreFactory.create(scope=scope,produceFile={file}))
            assertEquals(BrowserContentSize.MORE,reopened.browserContentSize("main").first())
            assertEquals(BrowserContentSize.LARGER,reopened.browserContentSize("homelab").first())
            assertEquals(BrowserContentSize.DEFAULT,reopened.browserContentSize("new-server").first())
            assertEquals(BrowserContentSize.DEFAULT,reopened.browserContentSize("old").first())
            reopened.setBrowserContentSize("main",BrowserContentSize.DEFAULT)
            assertEquals(BrowserContentSize.DEFAULT,reopened.browserContentSize("main").first())
            assertEquals(BrowserContentSize.LARGER,reopened.browserContentSize("homelab").first())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin();dispatcher.close() }
    }
}
