package com.agentshell.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.CronJob
import com.agentshell.data.model.DotFile
import com.agentshell.data.model.Host
import com.agentshell.data.model.SystemStats
import com.agentshell.data.model.TmuxSession
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val selectedHost: Host? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val systemStats: SystemStats? = null,
    val unreadAlertCount: Int = 0,
    // For CommandPalette
    val tmuxSessions: List<TmuxSession> = emptyList(),
    val acpSessions: List<AcpSession> = emptyList(),
    val cronJobs: List<CronJob> = emptyList(),
    val dotFiles: List<DotFile> = emptyList(),
    val currentTabIndex: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val wsService: WebSocketService,
    private val hostRepository: HostRepository,
    private val systemRepository: SystemRepository,
    private val dataStore: PreferencesDataStore,
) : ViewModel() {

    private val _tabIndex = MutableStateFlow(0)
    private val _unreadAlerts = MutableStateFlow(0)
    private val _tmuxSessions = MutableStateFlow<List<TmuxSession>>(emptyList())
    private val _acpSessions = MutableStateFlow<List<AcpSession>>(emptyList())
    private val _cronJobs = MutableStateFlow<List<CronJob>>(emptyList())
    private val _dotFiles = MutableStateFlow<List<DotFile>>(emptyList())

    private val _autoAttachSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoAttachSession: SharedFlow<String> = _autoAttachSession.asSharedFlow()
    private var autoAttachConsumed = false

    val claudeUsage = systemRepository.claudeUsage

    val uiState: StateFlow<HomeUiState> = combine(
        hostRepository.getSelectedHost(),
        wsService.connectionStatus,
        systemRepository.stats,
        _tabIndex,
        _unreadAlerts,
    ) { selectedHost, connStatus, stats, tabIndex, unreadAlerts ->
        HomeUiState(
            selectedHost = selectedHost,
            connectionStatus = connStatus,
            systemStats = stats,
            unreadAlertCount = unreadAlerts,
            tmuxSessions = _tmuxSessions.value,
            acpSessions = _acpSessions.value,
            cronJobs = _cronJobs.value,
            dotFiles = _dotFiles.value,
            currentTabIndex = tabIndex,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        restoreTabIndex()
        observeMessages()
        startStatsPolling()
        observeAndConnect()
        checkAutoAttach()
    }

    private fun restoreTabIndex() {
        viewModelScope.launch {
            dataStore.homeTabIndex.collect { saved ->
                _tabIndex.update { saved }
            }
        }
    }

    private fun checkAutoAttach() {
        viewModelScope.launch {
            // Wait for first CONNECTED status
            wsService.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            if (autoAttachConsumed) return@launch
            val enabled = dataStore.autoAttachEnabled.first()
            val sessionName = dataStore.lastSessionName.first()
            if (enabled && !sessionName.isNullOrBlank()) {
                autoAttachConsumed = true
                _autoAttachSession.emit(sessionName)
            }
        }
    }

    private fun observeAndConnect() {
        viewModelScope.launch {
            // Step 1: Ensure build-time hosts exist and one is selected (one-shot)
            val hosts = hostRepository.getHosts().first()
            if (hosts.isEmpty()) {
                val defaults = hostRepository.loadBuildTimeHosts()
                defaults.forEach { hostRepository.saveHost(it) }
                // Select first entry from SERVER_LIST (index 0 = sortOrder 0)
                val preferred = defaults.firstOrNull()
                preferred?.let { hostRepository.selectHost(it.id) }
            } else if (hostRepository.getSelectedHostOnce() == null) {
                hostRepository.selectHost(hosts.first().id)
            }

            // Step 2: Connect once to the selected host, reconnect only on host switch
            hostRepository.getSelectedHost()
                .filterNotNull()
                .distinctUntilChangedBy { it.id }
                .collect { host ->
                    val url = "${host.wsUrl}/ws"
                    val token = com.agentshell.core.config.BuildConfig.AUTH_TOKEN
                    wsService.connect(url, token.ifEmpty { null })
                }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { message ->
                when (message["type"] as? String) {
                    "system-stats", "stats" -> {
                        // Delegate to SystemRepository which owns the stats state
                        systemRepository.parseAndEmit(message)
                    }
                    "claude-usage" -> {
                        systemRepository.parseClaudeUsage(message)
                    }
                    "codex-usage" -> {
                        systemRepository.parseCodexUsage(message)
                    }
                    "sessions-list", "session_list" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["sessions"] as? List<Map<String, Any?>> ?: emptyList()
                        _tmuxSessions.update {
                            rawList.map { s ->
                                TmuxSession(
                                    name = s["name"] as? String ?: "",
                                    attached = (s["attached"] as? Boolean) ?: false,
                                    windows = (s["windows"] as? Number)?.toInt() ?: 1,
                                    created = s["created"] as? String,
                                    tool = s["tool"] as? String,
                                )
                            }
                        }
                    }
                    "acp-sessions-listed" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["sessions"] as? List<Map<String, Any?>> ?: emptyList()
                        _acpSessions.update {
                            rawList.map { s ->
                                AcpSession(
                                    sessionId = s["sessionId"] as? String ?: "",
                                    cwd = s["cwd"] as? String ?: "",
                                    title = s["title"] as? String ?: "",
                                    updatedAt = s["updatedAt"] as? String ?: "",
                                )
                            }
                        }
                    }
                    "cron-jobs-listed" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["jobs"] as? List<Map<String, Any?>> ?: emptyList()
                        _cronJobs.update {
                            rawList.map { j ->
                                CronJob(
                                    id = j["id"] as? String ?: "",
                                    name = j["name"] as? String ?: "",
                                    command = j["command"] as? String ?: "",
                                    schedule = j["schedule"] as? String ?: "",
                                    enabled = (j["enabled"] as? Boolean) ?: true,
                                )
                            }
                        }
                    }
                    "dotfiles-listed" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawList = message["files"] as? List<Map<String, Any?>> ?: emptyList()
                        _dotFiles.update {
                            rawList.map { f ->
                                DotFile(
                                    path = f["path"] as? String ?: "",
                                    name = f["name"] as? String ?: "",
                                    isDirectory = (f["isDirectory"] as? Boolean) ?: false,
                                    size = (f["size"] as? Number)?.toLong() ?: 0L,
                                )
                            }
                        }
                    }
                    "notification" -> {
                        _unreadAlerts.update { it + 1 }
                    }
                }
            }
        }
    }

    private fun startStatsPolling() {
        viewModelScope.launch {
            while (true) {
                if (wsService.isConnected) {
                    systemRepository.requestStats()
                }
                delay(5_000)
            }
        }
        viewModelScope.launch {
            // Wait for connection before first request
            wsService.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            while (true) {
                systemRepository.requestClaudeUsage()
                systemRepository.requestCodexUsage()
                delay(300_000) // 5 minutes — Anthropic rate-limits this endpoint
            }
        }
    }

    fun setTabIndex(index: Int) {
        _tabIndex.update { index }
        viewModelScope.launch { dataStore.setHomeTabIndex(index) }
    }

    fun clearUnreadAlerts() {
        _unreadAlerts.update { 0 }
    }
}
