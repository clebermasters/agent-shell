package com.agentshell

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.core.theme.LocalThemeControls
import com.agentshell.core.theme.ThemeControls
import com.agentshell.data.local.AppDatabase
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.repository.*
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.TranscriptionQueueService
import com.agentshell.data.services.WhisperService
import com.agentshell.feature.browser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import java.util.UUID

/** Production BrowserScreen/ViewModel with an isolated host database and loopback test TLS. */
class BrowserFlowPocActivity : ComponentActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var database:AppDatabase
    private lateinit var audio:AudioService
    private lateinit var transcriptions:TranscriptionQueueService
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        check(com.agentshell.core.config.BuildConfig.AUTH_TOKEN=="isolated-ui-poc-only")
        database=Room.inMemoryDatabaseBuilder(this,AppDatabase::class.java).build()
        val preferences=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=scope,
            produceFile={java.io.File(cacheDir,"browser-${UUID.randomUUID()}.preferences_pb")})
        val settings=PreferencesDataStore(preferences)
        val hosts=HostRepository(database.hostDao(),settings)
        audio=AudioService(this)
        transcriptions=TranscriptionQueueService(this,WhisperService())
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
                return BrowserViewModel(hosts,repository,SavedStateHandle(),audio,transcriptions,settings) as T
            }
        })[BrowserViewModel::class.java]
        val fixtureTranscript=intent.getStringExtra("voiceTranscript")
        Log.d("BrowserVoiceFixture","voice extra present=${intent.hasExtra("voiceTranscript")}; string present=${fixtureTranscript!=null}")
        fixtureTranscript?.let { transcript ->
            lifecycleScope.launch {
                model.voiceUiState.map { "pending=${it.transcripts.size}; delivery=${it.delivery!=null}; error=${it.error!=null}" }
                    .distinctUntilChanged().collect { Log.d("BrowserVoiceFixture",it) }
            }
            lifecycleScope.launch {
                model.uiState.onEach { Log.d("BrowserVoiceFixture","browser status=${it.status}") }.first { it.status=="connected" }
                Log.d("BrowserVoiceFixture","connected observed")
                delay(intent.getLongExtra("voiceDelayMs",3000L).coerceIn(0L,30000L))
                Log.d("BrowserVoiceFixture","completion status=${model.uiState.value.status}; accepted=${model.receiveFixtureTranscript(transcript)}")
            }
        }
        setContent {
            var mode by remember { mutableStateOf("light") }
            CompositionLocalProvider(LocalThemeControls provides ThemeControls(mode) { mode=it }) {
                AgentShellTheme(darkTheme=mode=="dark") { BrowserScreen(onBack={finish()},viewModel=model) }
            }
        }
    }
    override fun onDestroy() {
        if(::audio.isInitialized) audio.dispose()
        if(::transcriptions.isInitialized) transcriptions.dispose()
        scope.cancel();super.onDestroy();if(::database.isInitialized) database.close()
    }
}
