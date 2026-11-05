package com.agentshell.core.power

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ConnectionModeViewModel @Inject constructor(private val coordinator: ConnectionLifecycleCoordinator) : ViewModel() {
    val state = coordinator.state
    fun selectMode(mode: ConnectionMode) = coordinator.selectMode(mode)
    fun selectTimer(minutes: Int) = coordinator.selectTimer(minutes)
    fun resume() = coordinator.onForeground()
}
