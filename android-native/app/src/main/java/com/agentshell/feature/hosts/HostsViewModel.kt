package com.agentshell.feature.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.Host
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HostsUiState(
    val hosts: List<Host> = emptyList(),
    val selectedHost: Host? = null,
    val isLoading: Boolean = true,
    val testConnectionResult: TestConnectionResult? = null,
)

sealed class TestConnectionResult {
    data object Testing : TestConnectionResult()
    data object Success : TestConnectionResult()
    data class Failure(val message: String) : TestConnectionResult()
}

@HiltViewModel
class HostsViewModel @Inject constructor(
    private val repository: HostRepository,
    private val webSocketService: WebSocketService,
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    private val _testResult = MutableStateFlow<TestConnectionResult?>(null)

    val uiState: StateFlow<HostsUiState> = combine(
        repository.getHosts(),
        repository.getSelectedHost(),
        _isLoading,
        _testResult,
    ) { hosts, selectedHost, isLoading, testResult ->
        HostsUiState(
            hosts = hosts,
            selectedHost = selectedHost,
            isLoading = isLoading,
            testConnectionResult = testResult,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HostsUiState(),
    )

    init {
        viewModelScope.launch {
            // If no hosts exist, load build-time defaults
            repository.getHosts().collect { hosts ->
                if (hosts.isEmpty()) {
                    val buildTimeHosts = repository.loadBuildTimeHosts()
                    buildTimeHosts.forEach { repository.saveHost(it) }
                }
                _isLoading.update { false }
            }
        }
    }

    fun addHost(host: Host) {
        viewModelScope.launch {
            repository.saveHost(host)
            // Auto-select if no host is selected yet
            if (uiState.value.selectedHost == null) {
                repository.selectHost(host.id)
            }
        }
    }

    fun editHost(host: Host) {
        viewModelScope.launch {
            repository.saveHost(host)
        }
    }

    fun deleteHost(id: String) {
        viewModelScope.launch {
            val hosts = uiState.value.hosts
            if (hosts.size <= 1) return@launch // Prevent deleting the only host
            repository.deleteHost(id)
        }
    }

    fun selectHost(host: Host) {
        viewModelScope.launch {
            repository.selectHost(host.id)
            // HomeViewModel.observeAndConnect() will detect the host change and reconnect
        }
    }

    fun testConnection(host: Host) {
        viewModelScope.launch {
            _testResult.update { TestConnectionResult.Testing }
            try {
                // Use OkHttp directly to test WebSocket connectivity without
                // affecting the app's shared WebSocketService singleton.
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url("${host.wsUrl}/ws").build()
                var connected = false
                val latch = java.util.concurrent.CountDownLatch(1)
                client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        connected = true
                        latch.countDown()
                        webSocket.close(1000, "test")
                    }
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        latch.countDown()
                    }
                })
                // Wait up to 5 s for result
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                client.dispatcher.executorService.shutdown()
                _testResult.update {
                    if (connected) TestConnectionResult.Success
                    else TestConnectionResult.Failure("Could not connect to ${host.address}:${host.port}")
                }
            } catch (e: Exception) {
                _testResult.update { TestConnectionResult.Failure(e.message ?: "Unknown error") }
            }
        }
    }

    fun clearTestResult() {
        _testResult.update { null }
    }
}
