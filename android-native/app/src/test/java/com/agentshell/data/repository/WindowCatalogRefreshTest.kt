package com.agentshell.data.repository

import org.junit.Assert.*
import org.junit.Test

class WindowCatalogRefreshTest {
    @Test fun eightSessionsWithOneCatalogPerSecondCannotGenerateEightQueriesPerSecond() {
        val refresh = WindowCatalogRefresh()
        var queries = 0
        repeat(1_800) { second ->
            repeat(8) { session ->
                if (refresh.catalog("host", "s$session", "creation", 1, second * 1_000L)) queries++
            }
        }
        assertEquals(8 * 30, queries)
    }

    @Test fun changedWindowCountOrSessionIdentityRefreshesImmediately() {
        val refresh = WindowCatalogRefresh()
        assertTrue(refresh.catalog("host", "session", "first", 1, 0))
        assertFalse(refresh.catalog("host", "session", "first", 1, 1))
        assertTrue(refresh.catalog("host", "session", "first", 2, 2))
        assertTrue(refresh.catalog("host", "session", "recreated", 2, 3))
    }

    @Test fun periodicRefreshDiscoversSameCountWindowReplacementAndReconnectResetsItsClock() {
        val refresh = WindowCatalogRefresh()
        refresh.catalog("host", "session", "first", 1, 0)
        assertFalse(refresh.due("host", "session", 59_999))
        assertTrue(refresh.due("host", "session", 60_000))
        refresh.requested("host", "session", 61_000)
        assertFalse(refresh.due("host", "session", 120_000))
        assertTrue(refresh.due("host", "session", 121_000))
    }

    @Test fun hostSwitchAndDeletionCannotReuseAnotherSessionsCooldown() {
        val refresh = WindowCatalogRefresh()
        refresh.catalog("a", "same", "creation", 1, 0)
        assertTrue(refresh.catalog("b", "same", "creation", 1, 1))
        refresh.retain("b", emptySet())
        assertTrue(refresh.catalog("b", "same", "creation", 1, 2))
    }

    @Test fun delayedSocketOpenSchedulesAtItsActualDeadlineInsteadOfSkippingAMinute() {
        val refresh = WindowCatalogRefresh()
        refresh.catalog("host", "session", "creation", 1, 0)
        refresh.requested("host", "session", 100)
        assertEquals(100L, refresh.nextDelay("host", listOf("session"), 60_000))
        assertFalse(refresh.due("host", "session", 60_000))
        assertTrue(refresh.due("host", "session", 60_100))
    }
}
