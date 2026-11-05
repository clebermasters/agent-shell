package com.agentshell.core.power

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTransport { NONE, WIFI, CELLULAR, VPN, ETHERNET, OTHER }

data class BackgroundWorkState(
    val foreground: Boolean = false,
    val interactive: Boolean = true,
    val metered: Boolean = false,
    val charging: Boolean = false,
    val connected: Boolean = true,
    val transport: NetworkTransport = NetworkTransport.OTHER,
)

/** Shared eligibility signals. Reading these never creates a timer or acquires a wake lock. */
@Singleton
class BackgroundWorkPolicy @Inject constructor() {
    private var startedActivities = 0
    private val mutableState = MutableStateFlow(BackgroundWorkState())
    val state: StateFlow<BackgroundWorkState> = mutableState.asStateFlow()
    private val automaticAllowed = MutableStateFlow(true)
    val automaticNetworkingAllowed: StateFlow<Boolean> = automaticAllowed.asStateFlow()

    fun setAutomaticNetworkingAllowed(allowed: Boolean) { automaticAllowed.value = allowed }

    @Synchronized fun activityStarted() {
        startedActivities++
        refreshForeground()
    }

    @Synchronized fun activityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        refreshForeground()
    }

    @Synchronized fun updateDevice(
        interactive: Boolean = mutableState.value.interactive,
        metered: Boolean = mutableState.value.metered,
        charging: Boolean = mutableState.value.charging,
        connected: Boolean = mutableState.value.connected,
        transport: NetworkTransport = mutableState.value.transport,
    ) {
        mutableState.value = BackgroundWorkState(startedActivities > 0 && interactive, interactive,
            metered, charging, connected, transport)
    }

    private fun refreshForeground() {
        mutableState.value = mutableState.value.copy(foreground = startedActivities > 0 && mutableState.value.interactive)
    }
}
