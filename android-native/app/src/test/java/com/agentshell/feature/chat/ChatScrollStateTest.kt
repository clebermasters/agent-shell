package com.agentshell.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollStateTest {
    private fun action(
        initialized: Boolean = true,
        following: Boolean = true,
        scrolling: Boolean = false,
        atEnd: Boolean = false,
        ready: Boolean = true,
        active: Boolean = true,
        unreadIndex: Int = -1,
        messageCount: Int = 30,
    ) = chatScrollAction(active, ready, initialized, following, scrolling, atEnd, messageCount, unreadIndex)

    @Test fun newReplyOrStreamingGrowthFollowsTheActualEnd() {
        assertEquals(ChatScrollAction.FollowLatest, action())
        assertEquals(ChatScrollAction.Wait, action(atEnd = true))
    }

    @Test fun manualScrollingTakesPriorityEvenBeforeReadTrackingIsReady() {
        assertEquals(ChatScrollAction.Pause, action(scrolling = true))
        assertEquals(ChatScrollAction.Pause, action(scrolling = true, ready = false, initialized = false))
    }

    @Test fun anOlderReaderStaysPausedWhileTheLastReplyIsPartiallyVisible() {
        assertEquals(ChatScrollAction.Wait, action(following = false, atEnd = false))
        assertEquals(ChatScrollAction.ResumeFollowing, action(following = false, atEnd = true))
    }

    @Test fun unreadHistoryOpensAtTheBoundaryWithoutResettingAnExistingReader() {
        assertEquals(ChatScrollAction.OpenUnread, action(initialized = false, unreadIndex = 12))
        assertEquals(ChatScrollAction.OpenLatest, action(initialized = false))
        assertEquals(ChatScrollAction.Wait, action(following = false, unreadIndex = 12))
    }

    @Test fun backgroundEmptyAndLoadingScreensDoNotMoveTheList() {
        assertEquals(ChatScrollAction.Wait, action(active = false))
        assertEquals(ChatScrollAction.Wait, action(messageCount = 0))
        assertEquals(ChatScrollAction.Wait, action(ready = false))
    }
}
