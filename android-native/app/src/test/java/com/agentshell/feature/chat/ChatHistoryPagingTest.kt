package com.agentshell.feature.chat

import org.junit.Assert.*
import org.junit.Test

class ChatHistoryPagingTest {
    @Test fun interruptedCacheDoesNotAdvanceRemoteCursorPastMissingMiddle() {
        // The screen contains 100 old rows plus a recent 30-row window. That is
        // 130 displayed rows, but only 30 contiguous remote rows were fetched.
        assertEquals(30, remoteChatHistoryOffset(30, 130, cacheComplete = false, overlaps = true))
        assertEquals(130, advanceRemoteChatHistoryOffset(30, 100, 230, cacheComplete = false))
        assertTrue(preferRemoteChatHistory(connected = true, cacheComplete = false, remoteHasMore = true, hasGap = false))
    }

    @Test fun offlineReadersCanStillLoadEverySavedPageFromAnIncompleteCache() {
        assertFalse(preferRemoteChatHistory(connected = false, cacheComplete = false, remoteHasMore = true, hasGap = true))
        assertFalse(preferRemoteChatHistory(connected = true, cacheComplete = false, remoteHasMore = false, hasGap = false))
    }

    @Test fun completeCacheCanSupplyContiguousRemoteOffsetOnlyWhenHeadOverlaps() {
        assertEquals(100, remoteChatHistoryOffset(30, 100, cacheComplete = true, overlaps = true))
        assertEquals(30, remoteChatHistoryOffset(30, 130, cacheComplete = true, overlaps = false))
        assertFalse(preferRemoteChatHistory(connected = true, cacheComplete = true, remoteHasMore = true, hasGap = false))
        assertTrue(preferRemoteChatHistory(connected = true, cacheComplete = true, remoteHasMore = true, hasGap = true))
    }
}
