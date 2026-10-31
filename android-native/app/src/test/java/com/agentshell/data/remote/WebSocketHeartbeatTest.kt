package com.agentshell.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketHeartbeatTest {
    @Test fun anImmediatePongCancelsTheDeadlineBeforeSendReturns() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        heartbeat.ping(1, 100) { heartbeat.pong(1); true }
        advanceTimeBy(101); runCurrent()
        assertTrue(expired.isEmpty())
    }

    @Test fun aMissingPongFinishesTheForegroundProbeWithoutWaitingForPeriodicPing() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        heartbeat.ping(1, 100) { true }
        advanceTimeBy(99); runCurrent(); assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(1L), expired)
        advanceTimeBy(100); runCurrent(); assertEquals(listOf(1L), expired)
    }

    @Test fun repeatedHealthChecksCannotPostponeTheOutstandingDeadline() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        var sent = 0
        heartbeat.ping(1, 100) { sent++; true }
        advanceTimeBy(80); runCurrent()
        heartbeat.ping(1, 100) { sent++; true }
        advanceTimeBy(20); runCurrent()
        assertEquals(1, sent)
        assertEquals(listOf(1L), expired)
    }

    @Test fun aForegroundProbeShortensAnOutstandingPeriodicDeadline() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        heartbeat.ping(1, 200) { true }
        advanceTimeBy(50); runCurrent()
        heartbeat.ping(1, 100) { true }
        advanceTimeBy(99); runCurrent(); assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(1L), expired)
    }

    @Test fun aPongFromThePreviousSocketCannotCancelTheNewSocketProbe() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        heartbeat.ping(1, 100) { true }
        heartbeat.ping(2, 100) { true }
        heartbeat.pong(1)
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(2L), expired)
    }

    @Test fun disconnectingCancelsTheProbeAndARejectedSendRecoversImmediately() = runTest {
        val expired = mutableListOf<Long>()
        val heartbeat = WebSocketHeartbeat(this, { testScheduler.currentTime }, expired::add)
        heartbeat.ping(1, 100) { true }; heartbeat.cancel()
        advanceTimeBy(101); runCurrent(); assertTrue(expired.isEmpty())
        heartbeat.ping(2, 100) { false }
        assertEquals(listOf(2L), expired)
        advanceTimeBy(101); runCurrent(); assertEquals(listOf(2L), expired)
    }
}
