package com.agentshell.core.power

import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkPolicyTest {
    @Test fun screenOffAlwaysRestrictsAutomaticWorkEvenIfAnActivityRemainsStarted() {
        val policy = BackgroundWorkPolicy()
        policy.activityStarted()
        assertTrue(policy.state.value.foreground)
        policy.updateDevice(interactive = false)
        assertFalse(policy.state.value.foreground)
        policy.updateDevice(interactive = true)
        assertTrue(policy.state.value.foreground)
        policy.activityStopped()
        assertFalse(policy.state.value.foreground)
    }

    @Test fun overlappingActivitiesDoNotLoseForegroundStateAndDeviceChangesPreserveEachOther() {
        val policy = BackgroundWorkPolicy()
        policy.activityStarted(); policy.activityStarted(); policy.activityStopped()
        assertTrue(policy.state.value.foreground)
        policy.updateDevice(metered = true, transport = NetworkTransport.CELLULAR)
        policy.updateDevice(charging = true)
        assertTrue(policy.state.value.metered)
        assertEquals(NetworkTransport.CELLULAR, policy.state.value.transport)
        assertTrue(policy.state.value.charging)
        policy.updateDevice(connected = false, transport = NetworkTransport.NONE)
        assertFalse(policy.state.value.connected)
        policy.activityStopped(); policy.activityStopped()
        assertFalse(policy.state.value.foreground)
    }
}
