package com.agentshell.feature.deployment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.DeploymentPreferences
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class DeploymentViewModel @Inject constructor(
    hosts: HostRepository,
    private val preferences: PreferencesDataStore,
) : ViewModel() {
    private val _state = MutableStateFlow<DeploymentPreferences?>(null)
    val state = _state.asStateFlow()
    private val _saveErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveErrors = _saveErrors.asSharedFlow()

    init {
        viewModelScope.launch {
            hosts.getSelectedHost().map { it?.id }.distinctUntilChanged().collectLatest { hostId ->
                _state.value = null
                if (hostId != null) {
                    preferences.deploymentPreferences(hostId)
                        .catch { error ->
                            if (error !is IOException) throw error
                            _saveErrors.emit("Could not load Deployment for this server.")
                        }
                        .collect { _state.value = it }
                }
            }
        }
    }

    fun openChat(sessionName: String, windowIndex: Int, isAcp: Boolean, cwd: String = "") {
        val hostId = state.value?.hostId ?: return
        openChat(ChatTarget.create(hostId, sessionName, windowIndex, isAcp, cwd))
    }

    fun openChat(target: ChatTarget) {
        if (target.hostId != state.value?.hostId || target.sessionName.isBlank() || target.windowIndex < 0) return
        update(target.hostId) { it.withChat(target) }
    }

    fun closeSplit() = updateLayout { it.copy(enabled = false) }
    fun setOrientation(orientation: ChatSplitOrientation) = updateLayout { it.copy(orientation = orientation) }
    fun swapPanes() = updateLayout { it.copy(chatFirst = !it.chatFirst) }
    fun resize(orientation: ChatSplitOrientation, fraction: Float) = updateLayout { it.withFraction(orientation, fraction) }
    fun resetSizes() = updateLayout {
        val defaults = ChatSplitPreferences()
        it.copy(stackedFraction = defaults.stackedFraction, sideBySideFraction = defaults.sideBySideFraction)
    }

    private fun updateLayout(transform: (ChatSplitPreferences) -> ChatSplitPreferences) {
        val hostId = state.value?.hostId ?: return
        update(hostId) { it.copy(layout = transform(it.layout)) }
    }

    private fun update(hostId: String, transform: (DeploymentPreferences) -> DeploymentPreferences) {
        // Enqueue in tap order. Leaving the screen must not cancel an edit already requested.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    edits.withLock { preferences.updateDeploymentPreferences(hostId, transform) }
                } catch (_: IOException) {
                    _saveErrors.tryEmit("Could not save Deployment. Try the change again.")
                }
            }
        }
    }

    private companion object {
        val edits = Mutex()
    }
}
