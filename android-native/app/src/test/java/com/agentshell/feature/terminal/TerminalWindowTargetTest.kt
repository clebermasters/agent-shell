package com.agentshell.feature.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalWindowTargetTest {
    @Test fun sessionWithoutWindowZeroUsesItsActiveWindow() {
        assertEquals(1, activeTerminalWindow(listOf(mapOf("id" to 1.0, "active" to true))))
    }

    @Test fun sessionNavigationUsesActiveWindowInsteadOfFirstWindow() {
        assertEquals(7, activeTerminalWindow(listOf(
            mapOf("id" to 0, "active" to false), mapOf("id" to 7, "active" to true),
        )))
        assertEquals(0, activeTerminalWindow(listOf(
            mapOf("id" to 7, "active" to false), mapOf("id" to 0, "active" to true),
        )))
    }

    @Test fun singleWindowCanBeResolvedWithoutActiveMetadata() {
        assertEquals(3, activeTerminalWindow(listOf(mapOf("id" to 3))))
    }

    @Test fun missingOrAmbiguousWindowsNeverGuessZero() {
        assertNull(activeTerminalWindow(emptyList<Any>()))
        assertNull(activeTerminalWindow(listOf(mapOf("id" to 1), mapOf("id" to 2))))
        assertNull(activeTerminalWindow(listOf(mapOf("id" to 1, "active" to true), mapOf("id" to 2, "active" to true))))
        assertNull(activeTerminalWindow(listOf(mapOf("id" to -1, "active" to true))))
        assertNull(activeTerminalWindow(listOf(mapOf("id" to 1.5, "active" to true))))
    }
}
