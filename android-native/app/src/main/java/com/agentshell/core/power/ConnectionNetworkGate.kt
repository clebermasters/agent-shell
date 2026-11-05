package com.agentshell.core.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runtime network permission, separate from physical connectivity and user connection intent. */
object ConnectionNetworkGate {
    private val mutableAllowed = MutableStateFlow(true)
    val allowed: StateFlow<Boolean> = mutableAllowed.asStateFlow()

    fun setAllowed(allowed: Boolean) { mutableAllowed.value = allowed }
}
