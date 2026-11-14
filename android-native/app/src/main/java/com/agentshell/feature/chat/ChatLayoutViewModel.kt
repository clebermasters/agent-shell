package com.agentshell.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import com.agentshell.data.repository.HostRepository
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Session UI preferences are independent of the conversation currently bound to its pane. */
@HiltViewModel
class ChatLayoutViewModel @Inject constructor(
    private val hosts: HostRepository,
    private val preferences: PreferencesDataStore,
    webSocketService: WebSocketService,
) : ViewModel() {
    private data class Target(val hostId: String, val session: String, val window: Int)
    private var target: Target? = null
    private var loadJob: Job? = null
    val connectionStatus = webSocketService.connectionStatus
    private val _layout = MutableStateFlow<ChatSplitPreferences?>(null)
    val layout = _layout.asStateFlow()
    private val _saveErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveErrors = _saveErrors.asSharedFlow()

    fun bind(sessionName: String, windowIndex: Int, isAcp: Boolean) {
        loadJob?.cancel()
        target = null
        _layout.value = null
        if (isAcp) return // ACP conversations do not have a corresponding tmux terminal.
        loadJob = viewModelScope.launch {
            try {
                val host = hosts.getSelectedHostOnce() ?: return@launch
                val nextTarget = Target(host.id, sessionName, windowIndex)
                // A just-closed chat may still be finishing its last disk write.
                val saved = diskMutex.withLock {
                    preferences.chatSplitPreferences(host.id, sessionName, windowIndex).first()
                }
                target = nextTarget
                _layout.value = saved
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _saveErrors.emit("Could not load this chat's layout. Reopen the chat to retry.")
            }
        }
    }

    fun toggleSplit() = update { it.copy(enabled = !it.enabled) }
    fun setOrientation(orientation: ChatSplitOrientation) = update { it.copy(orientation = orientation) }
    fun swapPanes() = update { it.copy(chatFirst = !it.chatFirst) }
    fun resize(orientation: ChatSplitOrientation, fraction: Float) = update { it.withFraction(orientation, fraction) }
    fun resetSizes() = update { it.copy(stackedFraction = 0.6f, sideBySideFraction = 0.5f) }

    private fun update(transform: (ChatSplitPreferences) -> ChatSplitPreferences) {
        val selected = target ?: return
        val next = transform(_layout.value ?: return).normalized()
        _layout.value = next
        // Finish small, ordered DataStore transactions even if navigation clears this ViewModel.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                diskMutex.withLock {
                    try {
                        preferences.setChatSplitPreferences(selected.hostId, selected.session, selected.window, next)
                    } catch (_: Exception) {
                        _saveErrors.tryEmit("Could not save the split layout. Adjust it again to retry.")
                    }
                }
            }
        }
    }

    private companion object {
        // Navigation can create another ViewModel before the previous one's final edit finishes.
        val diskMutex = Mutex()
    }
}
