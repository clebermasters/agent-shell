package com.agentshell.core.power

import android.content.Context
import android.util.Log
import com.agentshell.core.diagnostics.BatteryDiagnostics
import com.agentshell.core.service.ConnectionService
import com.agentshell.core.service.ConnectionServiceRuntime
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConnectionModeUiState(
    val mode: ConnectionMode = ConnectionMode.LIVE,
    val liveUntilEpochMillis: Long? = null,
    val timerMinutes: Int = 0,
    val systemPaused: Boolean = false,
    val connectionDesired: Boolean = false,
    val backgroundPaused: Boolean = false,
    val ready: Boolean = false,
    val saveError: String? = null,
) {
    val pauseMessage: String? get() = when {
        !connectionDesired -> "Disconnected"
        systemPaused -> "Live updates paused — tap the Live control to resume"
        backgroundPaused -> "Battery Saver — updates resume when you return"
        else -> null
    }
}

/** Owns user mode/timer and permission to run; socket callbacks cannot grant that permission. */
@Singleton
class ConnectionLifecycleCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesDataStore,
    private val socket: WebSocketService,
    private val workPolicy: BackgroundWorkPolicy,
) {
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var timer: Job? = null
    private var scheduledDeadline: Long? = null
    private var saved = ConnectionModeState()
    private var pendingOverride: ConnectionModeState? = null
    private var loaded = false
    private var editSerial = 0L
    private var expiryWrite: Long? = null
    private var persistenceError: String? = null
    internal var nowMillis: () -> Long = System::currentTimeMillis
    private val mutableState = MutableStateFlow(ConnectionModeUiState())
    val state: StateFlow<ConnectionModeUiState> = mutableState.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (collector != null) return
        this.scope = scope
        // No network or wake retention until the persisted mode has been read.
        ConnectionNetworkGate.setAllowed(false)
        workPolicy.setAutomaticNetworkingAllowed(false)
        collector = scope.launch {
            launch {
                preferences.connectionMode.distinctUntilChanged().catch { error ->
                    if (error is CancellationException) throw error
                    persistenceError = "Could not load connection mode. Battery Saver is active."
                    emit(ConnectionModeState(mode = ConnectionMode.BATTERY_SAVER))
                }.collect { settings ->
                    saved = settings
                    loaded = true
                    if (pendingOverride == settings) pendingOverride = null
                    evaluate()
                }
            }
            combine(workPolicy.state, socket.connectionDesired, socket.connectionStatus, ConnectionServiceRuntime.state) {
                    _, _, _, _ -> Unit
                }.collect { evaluate() }
        }
    }

    /** Called from Activity resume/user interaction, not from network callbacks. */
    fun onForeground() {
        if (!workPolicy.state.value.foreground) return
        ConnectionServiceRuntime.clearBlockFromForeground()
        evaluate()
        if (ConnectionNetworkGate.allowed.value) socket.checkConnection()
    }

    fun refreshClock() {
        timer?.cancel(); timer = null; scheduledDeadline = null
        evaluate()
    }

    fun selectMode(mode: ConnectionMode) {
        val parent = scope ?: return
        if (!loaded) return
        val now = nowMillis()
        val serial = ++editSerial
        persistenceError = null
        val intended = (pendingOverride ?: saved).selectMode(mode, now)
        pendingOverride = intended
        if (workPolicy.state.value.foreground) ConnectionServiceRuntime.clearBlockFromForeground()
        evaluate() // Saver releases/pause resources before waiting for disk I/O.
        parent.launch {
            try {
                preferences.storeConnectionModeState(intended)
                if (editSerial == serial) {
                    saved = intended
                    pendingOverride = null
                    evaluate()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failEdit(serial)
            }
        }
    }

    fun selectTimer(minutes: Int) {
        require(minutes in ConnectionModeState.TIMER_PRESETS_MINUTES)
        val parent = scope ?: return
        if (!loaded) return
        val now = nowMillis()
        val serial = ++editSerial
        persistenceError = null
        val intended = (pendingOverride ?: saved).selectTimer(minutes, now)
        pendingOverride = intended
        evaluate()
        parent.launch {
            try {
                preferences.storeConnectionModeState(intended)
                if (editSerial == serial) {
                    saved = intended
                    pendingOverride = null
                    evaluate()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failEdit(serial)
            }
        }
    }

    private fun failEdit(serial: Long) {
        Log.w("ConnectionPolicy", "mode_preference_write_failed")
        if (editSerial != serial) return
        pendingOverride = (pendingOverride ?: saved).copy(mode = ConnectionMode.BATTERY_SAVER, liveUntilEpochMillis = null)
        persistenceError = "Could not save connection mode. Battery Saver is active for this session."
        evaluate()
    }

    private fun evaluate() {
        if (!loaded) return
        val settings = pendingOverride ?: saved
        val now = nowMillis()
        val runtime = ConnectionServiceRuntime.state.value
        val decision = connectionLifecycleDecision(settings, workPolicy.state.value,
            socket.connectionDesired.value, socket.connectionStatus.value == ConnectionStatus.CONNECTED,
            runtime.running, runtime.starting, runtime.blocked, now)
        val old = mutableState.value
        mutableState.value = ConnectionModeUiState(decision.mode,
            settings.liveUntilEpochMillis.takeIf { decision.mode == ConnectionMode.LIVE }, settings.timerMinutes,
            runtime.blocked && decision.mode == ConnectionMode.LIVE, socket.connectionDesired.value,
            !decision.networkAllowed && decision.mode == ConnectionMode.BATTERY_SAVER, true, persistenceError)
        if (old.ready && old.mode != decision.mode) BatteryDiagnostics.event("connection_mode_changed")
        ConnectionServiceRuntime.configure(decision.authorizeService, decision.wakeRequired,
            settings.liveUntilEpochMillis.takeIf { decision.mode == ConnectionMode.LIVE })
        if (!decision.authorizeService && (runtime.running || runtime.starting)) ConnectionService.stop(context)
        if (decision.networkAllowed) {
            workPolicy.setAutomaticNetworkingAllowed(socket.connectionDesired.value)
            ConnectionNetworkGate.setAllowed(true)
            socket.resumeAfterPolicyPause()
        } else {
            workPolicy.setAutomaticNetworkingAllowed(false)
            ConnectionNetworkGate.setAllowed(false)
            socket.suspendForBackground(if (runtime.blocked) "service-restricted" else "battery-saver")
        }
        if (decision.startService) ConnectionService.start(context)
        scheduleTimer(settings, now)
    }

    private fun scheduleTimer(settings: ConnectionModeState, now: Long) {
        val deadline = settings.liveUntilEpochMillis.takeIf { settings.mode == ConnectionMode.LIVE }
        if (deadline != null && deadline <= now) {
            timer?.cancel(); timer = null; scheduledDeadline = null
            if (pendingOverride == null && expiryWrite != deadline) {
                expiryWrite = deadline
                BatteryDiagnostics.event("connection_timer_expired")
                scope?.launch {
                    try { preferences.expireLiveTimer(deadline, nowMillis()) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.w("ConnectionPolicy", "timer_expiry_preference_write_failed")
                        persistenceError = "Could not save timer expiry. Battery Saver is active for this session."
                        // Keep the expired deadline latched; network/service
                        // emissions must not create a disk retry loop.
                        evaluate()
                    }
                }
            }
            return
        }
        if (scheduledDeadline == deadline && (deadline == null || timer?.isActive == true)) return
        timer?.cancel()
        scheduledDeadline = deadline
        timer = deadline?.let {
            scope?.launch {
                delay((it - nowMillis()).coerceAtLeast(1))
                scheduledDeadline = null
                evaluate() // Uses the current wall clock, never extends the saved deadline.
            }
        }
    }

    internal fun stopForTests() {
        collector?.cancel(); collector = null; timer?.cancel(); timer = null
        ConnectionService.stop(context)
        ConnectionNetworkGate.setAllowed(true)
        workPolicy.setAutomaticNetworkingAllowed(true)
    }
}
