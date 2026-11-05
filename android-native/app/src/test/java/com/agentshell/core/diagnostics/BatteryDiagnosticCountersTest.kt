package com.agentshell.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BatteryDiagnosticCountersTest {
    @Test fun disabledCollectionDropsTrafficAndEvents() {
        val counters = BatteryDiagnosticCounters()
        counters.transfer("PRIMARY", "chat-history", true, 900)
        counters.event("socket_attempt", DiagnosticSocketRole.PRIMARY)
        counters.enable(0)
        assertFalse(counters.drain(100).keys.any { it.contains("history") || it.contains("socket_attempt") })
    }

    @Test fun enablingDuringAnExistingConnectionPreservesLiveStateWithoutOldTraffic() {
        val counters = BatteryDiagnosticCounters()
        val token = Any()
        counters.socket(token, DiagnosticSocketRole.CHAT_MONITOR, true)
        counters.event("service_started")
        counters.wakeLock(true, 10)
        counters.enable(100)
        val first = counters.drain(150)
        assertEquals(1L, first["sockets_CHAT_MONITOR"])
        assertEquals(1L, first["service_running"])
        assertEquals(50L, first["wake_held_ms"])
        assertEquals(50L, counters.drain(200)["wake_held_ms"])
        counters.wakeLock(false, 220)
        assertEquals(20L, counters.drain(300)["wake_held_ms"])
        assertEquals(0L, counters.drain(400)["wake_held_ms"])
    }

    @Test fun foregroundAndScreenOffTrafficHaveSeparateBucketsAndUnknownTypesStayPrivate() {
        val counters = BatteryDiagnosticCounters()
        counters.enable(0)
        counters.visibility(true, true)
        counters.transfer("OFFLINE_SYNC", "history-page", true, 123)
        counters.visibility(false, false)
        counters.transfer("CHAT_MONITOR", "chat-event", true, 45)
        counters.transfer("PRIMARY", "secret-token\nhttps://private/chat", false, 8)
        counters.event("private exception text")
        val frame = counters.drain(1)
        assertEquals(123L, frame["foreground.screen_on.OFFLINE_SYNC.history.rx_bytes"])
        assertEquals(45L, frame["background.screen_off.CHAT_MONITOR.chat.rx_bytes"])
        assertEquals(8L, frame["background.screen_off.PRIMARY.other.tx_bytes"])
        assertFalse(frame.keys.any { it.contains("secret") || it.contains("https") || it.contains("exception") })
        assertFalse(counters.drain(2).keys.any { it.endsWith("rx_bytes") || it.endsWith("tx_bytes") })
    }

    @Test fun reconnectAndDisposeDoNotAccumulateSocketCounts() {
        val counters = BatteryDiagnosticCounters()
        val token = Any()
        repeat(100) {
            counters.socket(token, DiagnosticSocketRole.SESSION, true)
            counters.socket(token, DiagnosticSocketRole.SESSION, true)
            assertEquals(1L, counters.drain(0)["sockets_SESSION"])
            counters.socket(token, DiagnosticSocketRole.SESSION, false)
        }
        assertEquals(0L, counters.drain(0)["sockets_SESSION"])
    }

    @Test fun disablingAndReenablingCannotReplayEarlierCounters() {
        val counters = BatteryDiagnosticCounters()
        counters.enable(0)
        counters.event("cache_sync_started")
        counters.disable()
        counters.event("cache_sync_started")
        counters.enable(200)
        assertFalse(counters.drain(300).keys.any { it.contains("cache_sync_started") })
    }

    @Test fun intentionalSocketDisposalIsCountedOnceWithoutALateCallbackDoubleCount() {
        val counters = BatteryDiagnosticCounters()
        val token = Any()
        counters.enable(0)
        counters.socket(token, DiagnosticSocketRole.OFFLINE_SYNC, true)
        counters.socket(token, DiagnosticSocketRole.OFFLINE_SYNC, false)
        counters.socket(token, DiagnosticSocketRole.OFFLINE_SYNC, false)
        val frame = counters.drain(100)
        assertEquals(0L, frame["sockets_OFFLINE_SYNC"])
        assertEquals(1L, frame["background.screen_off.OFFLINE_SYNC.socket_disconnected"])
    }

    @Test fun httpOutcomeBucketsDistinguishEmptySuccessFromUnreadErrorWithoutRawDetails() {
        val counters = BatteryDiagnosticCounters()
        counters.enable(0)
        counters.httpStatus(404, "http_files")
        counters.httpStatus(200, "http_files")
        counters.httpStatus(503, "http_files")
        counters.httpFailure(true, "http_files")
        counters.httpFailure(false, "http_files")
        counters.event("attachment_retry_scheduled")
        val frame = counters.drain(1)
        assertEquals(1L, frame["background.screen_off.HTTP.files.status_404"])
        assertEquals(1L, frame["background.screen_off.HTTP.files.status_2xx"])
        assertEquals(1L, frame["background.screen_off.HTTP.files.status_5xx"])
        assertEquals(1L, frame["background.screen_off.HTTP.files.cancelled"])
        assertEquals(1L, frame["background.screen_off.HTTP.files.transport_failed"])
        assertEquals(1L, frame["background.screen_off.APP.attachment_retry_scheduled"])
        counters.disable()
        counters.httpStatus(404, "http_files")
        counters.enable(2)
        assertFalse(counters.drain(3).keys.any { it.contains("HTTP") })
    }
}
