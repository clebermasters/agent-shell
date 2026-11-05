package com.agentshell.core.power

import org.junit.Assert.*
import org.junit.Test

class ConnectionLifecyclePolicyTest {
    private fun decide(settings: ConnectionModeState = ConnectionModeState(), foreground: Boolean = false,
        desired: Boolean = true, connected: Boolean = true, running: Boolean = false,
        starting: Boolean = false, blocked: Boolean = false, network: Boolean = true, now: Long = 1_000) =
        connectionLifecycleDecision(settings, BackgroundWorkState(foreground = foreground, connected = network),
            desired, connected, running, starting, blocked, now)

    @Test fun liveDefaultStartsOnlyFromVisibleInteractionAndRetainsOnlyAConnectedBackgroundCpu() {
        val visible = decide(foreground = true)
        assertTrue(visible.startService); assertTrue(visible.networkAllowed); assertFalse(visible.wakeRequired)
        val live = decide(running = true)
        assertTrue(live.authorizeService); assertTrue(live.networkAllowed); assertTrue(live.wakeRequired)
        assertFalse(decide(running = true, connected = false).wakeRequired)
        assertFalse(decide(running = true, network = false).wakeRequired)
        val notStarted = decide()
        assertFalse(notStarted.startService); assertFalse(notStarted.networkAllowed); assertFalse(notStarted.wakeRequired)
    }

    @Test fun saverAllowsInteractiveWorkButNoBackgroundConnectionOrWake() {
        val saver = ConnectionModeState(mode = ConnectionMode.BATTERY_SAVER)
        assertTrue(decide(saver, foreground = true).networkAllowed)
        assertFalse(decide(saver, foreground = true).authorizeService)
        val hidden = decide(saver, running = true)
        assertFalse(hidden.networkAllowed); assertFalse(hidden.authorizeService); assertFalse(hidden.wakeRequired)
    }

    @Test fun elapsedDeadlineAndSystemRestrictionCannotBeOverriddenByReconnectState() {
        val expired = decide(ConnectionModeState(liveUntilEpochMillis = 1_000, timerMinutes = 15), running = true)
        assertEquals(ConnectionMode.BATTERY_SAVER, expired.mode)
        assertFalse(expired.authorizeService); assertFalse(expired.networkAllowed)
        val restricted = decide(foreground = true, running = true, starting = true, blocked = true)
        assertFalse(restricted.startService); assertFalse(restricted.authorizeService)
        assertFalse(restricted.networkAllowed); assertFalse(restricted.wakeRequired)
    }

    @Test fun manualPrimaryDisconnectDoesNotRequireAServiceButInteractiveShareSocketsRemainPossible() {
        val visible = decide(foreground = true, desired = false, running = true)
        assertTrue(visible.networkAllowed); assertFalse(visible.authorizeService); assertFalse(visible.wakeRequired)
        assertFalse(decide(desired = false, running = true).networkAllowed)
    }
}
