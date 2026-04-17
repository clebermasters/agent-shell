package com.agentshell.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.ContainerActionKind
import com.agentshell.data.model.ContainerRuntimeKind
import com.agentshell.data.model.MetricPoint
import com.agentshell.data.model.SystemStats
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.requestContainerAction
import com.agentshell.data.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SystemUiState(
    val stats: SystemStats? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val cpuHistory: List<MetricPoint> = emptyList(),
    val memoryHistory: List<MetricPoint> = emptyList(),
    val pendingContainerActions: Set<String> = emptySet(),
)

@HiltViewModel
class SystemViewModel @Inject constructor(
    private val repository: SystemRepository,
    private val wsService: WebSocketService,
) : ViewModel() {

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var refreshJob: Job? = null
    private var screenActive = false
    private var includeContainers = false

    init {
        observeStats()
        observeMessages()
    }

    fun refresh() {
        requestStats(showLoading = true)
    }

    fun setScreenActive(isActive: Boolean) {
        if (screenActive == isActive) return
        screenActive = isActive
        if (isActive) {
            startAutoRefresh()
        } else {
            refreshJob?.cancel()
            refreshJob = null
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun setContainerStatsEnabled(enabled: Boolean) {
        if (includeContainers == enabled) return
        includeContainers = enabled
        if (screenActive) {
            startAutoRefresh()
        }
    }

    fun performContainerAction(
        runtime: ContainerRuntimeKind,
        containerId: String,
        action: ContainerActionKind,
    ) {
        val actionKey = pendingActionKey(runtime, containerId)
        _state.update { it.copy(pendingContainerActions = it.pendingContainerActions + actionKey) }
        wsService.requestContainerAction(
            runtime = runtime.wireValue,
            containerId = containerId,
            action = action.wireValue,
        )
    }

    fun isContainerActionPending(runtime: ContainerRuntimeKind, containerId: String): Boolean =
        state.value.pendingContainerActions.contains(pendingActionKey(runtime, containerId))

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            requestStats()
            while (true) {
                delay(if (includeContainers) CONTAINER_REFRESH_INTERVAL_MS else OVERVIEW_REFRESH_INTERVAL_MS)
                requestStats()
            }
        }
    }

    private fun requestStats(showLoading: Boolean = false) {
        if (showLoading) {
            _state.update { it.copy(isLoading = true) }
        }
        repository.requestStats(includeContainers = includeContainers)
    }

    private fun observeStats() {
        viewModelScope.launch {
            repository.stats.collectLatest { stats ->
                _state.update { current ->
                    current.copy(
                        stats = stats,
                        isLoading = false,
                        cpuHistory = appendMetric(current.cpuHistory, stats?.timestamp, stats?.cpuUsage),
                        memoryHistory = appendMetric(current.memoryHistory, stats?.timestamp, stats?.memoryUsage),
                    )
                }
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { msg ->
                when (msg["type"] as? String) {
                    "container-action-result" -> {
                        val runtime = parseRuntime(msg["runtime"] as? String)
                        val containerId = msg["containerId"] as? String ?: return@collect
                        val action = parseAction(msg["action"] as? String)
                        val success = msg["success"] as? Boolean ?: false
                        val error = msg["error"] as? String

                        _state.update { current ->
                            current.copy(
                                pendingContainerActions = current.pendingContainerActions -
                                    pendingActionKey(runtime, containerId),
                            )
                        }

                        val eventMessage = if (success) {
                            "${runtime.label}: ${action.label} requested for ${containerId.take(12)}"
                        } else {
                            error ?: "${runtime.label}: ${action.label} failed"
                        }
                        _events.emit(eventMessage)
                        if (success) {
                            repository.requestStats(includeContainers = true)
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }

    private fun appendMetric(
        history: List<MetricPoint>,
        timestamp: Long?,
        value: Double?,
    ): List<MetricPoint> {
        if (timestamp == null || value == null) return history
        val next = if (history.lastOrNull()?.timestamp == timestamp) {
            history.dropLast(1) + MetricPoint(timestamp, value)
        } else {
            history + MetricPoint(timestamp, value)
        }
        return next.takeLast(MAX_HISTORY_POINTS)
    }

    private fun pendingActionKey(runtime: ContainerRuntimeKind, containerId: String): String =
        "${runtime.wireValue}:$containerId"

    private fun parseRuntime(raw: String?): ContainerRuntimeKind = when (raw?.lowercase()) {
        "podman" -> ContainerRuntimeKind.PODMAN
        else -> ContainerRuntimeKind.DOCKER
    }

    private fun parseAction(raw: String?): ContainerActionKind = when (raw?.lowercase()) {
        "start" -> ContainerActionKind.START
        "stop" -> ContainerActionKind.STOP
        "restart" -> ContainerActionKind.RESTART
        "kill" -> ContainerActionKind.KILL
        "pause" -> ContainerActionKind.PAUSE
        "resume" -> ContainerActionKind.RESUME
        else -> ContainerActionKind.RESTART
    }

    private companion object {
        const val MAX_HISTORY_POINTS = 24
        const val OVERVIEW_REFRESH_INTERVAL_MS = 5_000L
        const val CONTAINER_REFRESH_INTERVAL_MS = 10_000L
    }
}
