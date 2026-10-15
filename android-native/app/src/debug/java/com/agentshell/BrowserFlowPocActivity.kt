package com.agentshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.core.theme.LocalThemeControls
import com.agentshell.core.theme.ThemeControls
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.repository.*
import com.agentshell.feature.browser.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.UUID

/** Production BrowserScreen/ViewModel with an isolated host database and loopback test TLS. */
class BrowserFlowPocActivity : ComponentActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var database:AppDatabase
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        check(com.agentshell.core.config.BuildConfig.AUTH_TOKEN=="isolated-ui-poc-only")
        database=Room.inMemoryDatabaseBuilder(this,AppDatabase::class.java).build()
        val preferences=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=scope,
            produceFile={java.io.File(cacheDir,"browser-${UUID.randomUUID()}.preferences_pb")})
        val hosts=HostRepository(database.hostDao(),PreferencesDataStore(preferences))
        runBlocking(Dispatchers.IO) {
            val legacy=intent.getBooleanExtra("legacyHttp",false)
            val host=Host("browser-fixture",if(legacy) "127.0.0.1" else "Local browser fixture",
                if(intent.getBooleanExtra("lanAlias",false)) "localhost" else "127.0.0.1",if(legacy) 18999 else 18998,tls=!legacy)
            hosts.saveHost(host)
            if(legacy) hosts.saveHost(Host("known-tls","Configured secure LAN","127.0.0.1",18998,tls=true))
            hosts.selectHost(host.id)
        }
        val model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            override fun <T:ViewModel> create(modelClass:Class<T>):T {
                @Suppress("UNCHECKED_CAST")
                val repository=object:BrowserRepository(OkHttpClient()) {
                    override suspend fun viewerTunnelAddress(host:Host):String? = if(intent.getBooleanExtra("lanTunnel",false)) "127.0.0.1" else super.viewerTunnelAddress(host)
                }
                return BrowserViewModel(hosts,repository,SavedStateHandle()) as T
            }
        })[BrowserViewModel::class.java]
        setContent {
            var mode by remember { mutableStateOf("light") }
            CompositionLocalProvider(LocalThemeControls provides ThemeControls(mode) { mode=it }) {
                AgentShellTheme(darkTheme=mode=="dark") { BrowserScreen(onBack={finish()},viewModel=model) }
            }
        }
    }
    override fun onDestroy() { scope.cancel();super.onDestroy();if(::database.isInitialized) database.close() }
}
