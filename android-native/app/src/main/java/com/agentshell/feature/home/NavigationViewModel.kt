package com.agentshell.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    preferencesDataStore: PreferencesDataStore,
    private val hostRepository: HostRepository,
    private val webSocketService: WebSocketService,
) : ViewModel() {
    val keepScreenOn: StateFlow<Boolean> = preferencesDataStore.keepScreenOn.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    suspend fun prepareChatNotification(target: ChatTarget): Boolean {
        val host = hostRepository.getHosts().first().firstOrNull { it.id == target.hostId } ?: return false
        hostRepository.selectHost(host.id)
        // Set the correct connection before the destination queues its chat watch.
        webSocketService.connect("${host.wsUrl}/ws", BuildConfig.AUTH_TOKEN.ifEmpty { null })
        return true
    }
}
