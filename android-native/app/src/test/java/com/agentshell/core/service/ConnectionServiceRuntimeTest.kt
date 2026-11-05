package com.agentshell.core.service

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionServiceRuntimeTest {
    private var now = 1_800_000_000_000L

    @Before fun setup() {
        ConnectionServiceRuntime.resetForTests()
        ConnectionServiceRuntime.nowEpochMillis = { now }
    }

    @After fun cleanup() = ConnectionServiceRuntime.resetForTests()

    @Test fun defaultStateDoesNotAuthorizeAServiceStart() {
        assertEquals(ConnectionServiceRuntimeState(), ConnectionServiceRuntime.state.value)
        assertNull(ConnectionServiceRuntime.beginStart())
    }

    @Test fun reconnectAndWakeChangesDoNotExtendTheSelectedDeadlineOrInvalidateTheStart() {
        val deadline = now + 60_000
        ConnectionServiceRuntime.configure(true, false, deadline)
        val generation = ConnectionServiceRuntime.beginStart()!!
        now += 10_000
        repeat(10) { ConnectionServiceRuntime.configure(true, true, deadline) }
        assertEquals(generation, ConnectionServiceRuntime.state.value.generation)
        assertEquals(deadline, ConnectionServiceRuntime.state.value.liveUntilEpochMillis)
        assertTrue(ConnectionServiceRuntime.currentStart(generation))
        assertTrue(ConnectionServiceRuntime.markRunning(generation))
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertFalse(ConnectionServiceRuntime.state.value.starting)
    }

    @Test fun configurationAndExpectedStopCannotClearAPlatformBlock() {
        ConnectionServiceRuntime.configure(true, true, null)
        ConnectionServiceRuntime.block(ConnectionServiceBlockReason.TIMEOUT)
        ConnectionServiceRuntime.prepareStop()
        ConnectionServiceRuntime.configure(false, false, null)
        ConnectionServiceRuntime.configure(true, true, now + 120_000)
        ConnectionServiceRuntime.block(ConnectionServiceBlockReason.SERVICE_STOPPED)
        assertEquals(ConnectionServiceBlockReason.TIMEOUT, ConnectionServiceRuntime.state.value.blockReason)
        assertNull(ConnectionServiceRuntime.beginStart())
        ConnectionServiceRuntime.clearBlockFromForeground()
        assertFalse(ConnectionServiceRuntime.state.value.blocked)
        assertNotNull(ConnectionServiceRuntime.beginStart())
    }

    @Test fun staleStartCannotClaimOrCancelANewerAuthorization() {
        ConnectionServiceRuntime.configure(true, true, null)
        val old = ConnectionServiceRuntime.beginStart()!!
        ConnectionServiceRuntime.configure(false, false, null)
        ConnectionServiceRuntime.configure(true, true, null)
        val current = ConnectionServiceRuntime.beginStart()!!
        ConnectionServiceRuntime.rejectStart(old)
        assertFalse(ConnectionServiceRuntime.markRunning(old))
        assertTrue(ConnectionServiceRuntime.currentStart(current))
        assertTrue(ConnectionServiceRuntime.markRunning(current))
    }

    @Test fun selectedDeadlineExpiryRevokesExecutionWithoutClearingAnExistingBlock() {
        ConnectionServiceRuntime.configure(true, true, now + 10)
        val generation = ConnectionServiceRuntime.beginStart()!!
        ConnectionServiceRuntime.markRunning(generation)
        ConnectionServiceRuntime.block(ConnectionServiceBlockReason.TIMEOUT)
        now += 10
        ConnectionServiceRuntime.expire()
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
        assertFalse(ConnectionServiceRuntime.state.value.wakeRequired)
        assertEquals(ConnectionServiceBlockReason.TIMEOUT, ConnectionServiceRuntime.state.value.blockReason)
    }

    @Test fun unexpectedDestructionLatchesButExpectedStopDoesNot() {
        ConnectionServiceRuntime.configure(true, true, null)
        val generation = ConnectionServiceRuntime.beginStart()!!
        ConnectionServiceRuntime.markRunning(generation)
        ConnectionServiceRuntime.stopped(generation, false)
        assertEquals(ConnectionServiceBlockReason.SERVICE_STOPPED, ConnectionServiceRuntime.state.value.blockReason)
        ConnectionServiceRuntime.clearBlockFromForeground()
        val second = ConnectionServiceRuntime.beginStart()!!
        ConnectionServiceRuntime.markRunning(second)
        ConnectionServiceRuntime.prepareStop()
        ConnectionServiceRuntime.stopped(second, false)
        assertFalse(ConnectionServiceRuntime.state.value.blocked)
        assertFalse(ConnectionServiceRuntime.state.value.running)
    }
}
