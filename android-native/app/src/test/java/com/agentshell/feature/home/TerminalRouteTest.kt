package com.agentshell.feature.home

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalRouteTest {
    @Test fun chatToTerminalNavigationRetainsItsExactWindow() {
        assertEquals("terminal/agentShell?isSwipeNav=false&windowIndex=1", Routes.terminal("agentShell", windowIndex = 1))
        assertEquals("terminal/agentShell?isSwipeNav=false&windowIndex=0", Routes.terminal("agentShell", windowIndex = 0))
    }

    @Test fun homeAndSwipeNavigationLeaveWindowSelectionUnspecified() {
        assertEquals("terminal/agentShell?isSwipeNav=false", Routes.terminal("agentShell"))
        assertEquals("terminal/agentShell?isSwipeNav=true", Routes.terminal("agentShell", isSwipeNav = true))
    }

    @Test fun sessionNamesRemainEncodedWhenAWindowIsRequested() {
        assertEquals("terminal/agent%20Shell%2Ftest?isSwipeNav=false&windowIndex=3", Routes.terminal("agent Shell/test", windowIndex = 3))
    }
}
