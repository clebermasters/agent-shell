package com.agentshell.core.power

import org.junit.Assert.*
import org.junit.Test

class ConnectionModeTest {
    @Test fun absentPreferenceDefaultsToLiveWithoutATimer() {
        val state = ConnectionModeCodec.decode(null)
        assertEquals(ConnectionMode.LIVE, state.mode)
        assertEquals(ConnectionMode.LIVE, state.effectiveMode(Long.MAX_VALUE))
        assertNull(state.liveUntilEpochMillis)
        assertEquals(0, state.timerMinutes)
    }

    @Test fun deadlineBecomesSaverExactlyAtExpiryBeforeAnyPersistenceCallback() {
        val state = ConnectionModeState(ConnectionMode.LIVE, liveUntilEpochMillis = 2_000, timerMinutes = 15)
        assertEquals(ConnectionMode.LIVE, state.effectiveMode(1_999))
        assertEquals(ConnectionMode.BATTERY_SAVER, state.effectiveMode(2_000))
        assertEquals(ConnectionMode.BATTERY_SAVER, state.effectiveMode(Long.MAX_VALUE))
        assertEquals(ConnectionMode.LIVE, state.mode)
    }

    @Test fun aDamagedPresentPreferenceFailsClosedWithoutExposingItsRawValue() {
        val secret = "private-token-never-log-this"
        val damaged = listOf("not-json $secret", "{}", "null",
            "{\"mode\":\"UNKNOWN\",\"timerMinutes\":0,\"secret\":\"$secret\"}",
            "{\"mode\":\"LIVE\",\"timerMinutes\":-1}",
            "{\"mode\":\"LIVE\",\"timerMinutes\":15}",
            "{\"mode\":\"LIVE\",\"timerMinutes\":0,\"liveUntilEpochMillis\":2000}")
        for (raw in damaged) {
            val state = ConnectionModeCodec.decode(raw)
            assertEquals(ConnectionMode.BATTERY_SAVER, state.effectiveMode(0))
            assertFalse(state.toString().contains(secret))
        }
    }

    @Test fun codecRoundTripPreservesDeadlineAndRememberedPreset() {
        val state = ConnectionModeState(ConnectionMode.LIVE, 1_234_567, 120)
        assertEquals(state, ConnectionModeCodec.decode(ConnectionModeCodec.encode(state)))
        val saver = ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 60)
        assertEquals(saver, ConnectionModeCodec.decode(ConnectionModeCodec.encode(saver)))
    }

    @Test fun timerArithmeticCannotWrapIntoThePast() {
        val state = ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 240)
            .selectMode(ConnectionMode.LIVE, Long.MAX_VALUE - 10)
        assertEquals(Long.MAX_VALUE, state.liveUntilEpochMillis)
        assertEquals(ConnectionMode.LIVE, state.effectiveMode(Long.MAX_VALUE - 1))
    }
}
