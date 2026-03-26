package com.agentshell.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.SystemStats
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SystemUiState(
    val stats: SystemStats? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SystemViewModel @Inject constructor(
    private val repository: SystemRepository,
    private val wsService: WebSocketService,
) : ViewModel() {

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        observeMessages()
        startAutoRefresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true) }
        repository.requestStats()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                repository.requestStats()
                delay(5_000L)
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            wsService.messages.collect { msg ->
                val type = msg["type"] as? String
                if (type == "stats" || type == "system-stats" || type == "system_stats") {
                    repository.parseAndEmit(msg)
                    _state.update { it.copy(stats = repository.stats.value, isLoading = false) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}
