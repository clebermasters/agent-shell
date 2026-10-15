package com.agentshell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.agentshell.core.config.BuildConfig
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.core.theme.LocalThemeControls
import com.agentshell.core.theme.ThemeControls
import com.agentshell.core.theme.resolvedDarkTheme
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import com.agentshell.feature.home.AgentShellNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Real navigation, Room, preferences and WebSocket stack; fake-token emulator builds only. */
@AndroidEntryPoint
class ServerSwitchPocActivity : ComponentActivity() {
    @Inject lateinit var hosts: HostRepository
    @Inject lateinit var preferences: PreferencesDataStore
    @Inject lateinit var socket: WebSocketService

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(BuildConfig.AUTH_TOKEN == "isolated-ui-poc-only")
        enableEdgeToEdge()
        lifecycleScope.launch {
            hosts.ensureDefaultHosts()
            val existing = hosts.getHosts().first().map { it.id }.toSet()
            listOf(Host("fixture-lab", "HOMELAB fixture", "127.0.0.1", 40200, sortOrder = 1),
                Host("fixture-lan", "LOCAL_LAN fixture", "127.0.0.1", 40201, sortOrder = 2)).forEach {
                if (it.id !in existing) hosts.saveHost(it)
            }
            preferences.setAutoAttachEnabled(false)
            preferences.setChatNotificationsEnabled(false)
            preferences.setAgentFinishedEnabled(false)
            preferences.setAgentQuietEnabled(false)
            val initialTheme = preferences.themeMode.first()
            setContent {
                val mode by preferences.themeMode.collectAsStateWithLifecycle(initialValue = initialTheme)
                CompositionLocalProvider(LocalThemeControls provides ThemeControls(mode) { lifecycleScope.launch { preferences.setThemeMode(it) } }) {
                    AgentShellTheme(darkTheme = resolvedDarkTheme(mode, isSystemInDarkTheme())) { AgentShellNavHost() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("queueOldInput", false)) {
            socket.disconnect()
            socket.send(mapOf("type" to "input-via-tmux", "sessionName" to "fixture-only", "data" to "DO_NOT_FORWARD"))
        }
    }
}
