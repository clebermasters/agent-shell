package com.agentshell.core.power

internal data class ConnectionLifecycleDecision(
    val mode: ConnectionMode,
    val networkAllowed: Boolean,
    val authorizeService: Boolean,
    val startService: Boolean,
    val wakeRequired: Boolean,
)

/** A service failure is latched until a fresh foreground action, never a retry timer. */
internal fun connectionLifecycleDecision(
    settings: ConnectionModeState,
    work: BackgroundWorkState,
    desired: Boolean,
    connected: Boolean,
    serviceRunning: Boolean,
    serviceStarting: Boolean,
    serviceBlocked: Boolean,
    nowMillis: Long,
): ConnectionLifecycleDecision {
    val mode = settings.effectiveMode(nowMillis)
    val live = mode == ConnectionMode.LIVE && desired && !serviceBlocked
    val foreground = work.foreground && work.interactive
    val authorize = live && (foreground || serviceRunning || serviceStarting)
    // Interactive share/session sockets remain usable after an intentional
    // primary disconnect. Each socket still owns its separate connection intent.
    val network = (foreground && (mode == ConnectionMode.BATTERY_SAVER || !serviceBlocked)) || (live && serviceRunning)
    return ConnectionLifecycleDecision(mode, network, authorize,
        live && foreground && !serviceRunning && !serviceStarting,
        authorize && serviceRunning && !foreground && connected && work.connected)
}
