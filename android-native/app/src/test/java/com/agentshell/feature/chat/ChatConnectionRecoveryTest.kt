package com.agentshell.feature.chat

import com.agentshell.data.model.ConnectionStatus
import org.junit.Assert.*
import org.junit.Test

class ChatConnectionRecoveryTest {
    @Test fun theFirstDisconnectAfterOpeningAnAlreadyConnectedChatRestoresItsWatch() {
        val recovery = ChatConnectionRecovery()
        recovery.onConnection(ConnectionStatus.CONNECTED, true)
        recovery.watchStarted(connected = true)
        recovery.onConnection(ConnectionStatus.OFFLINE, true)
        assertTrue(recovery.onConnection(ConnectionStatus.CONNECTED, true))
    }

    @Test fun aHiddenBackStackChatCannotReplaceTheVisibleChatAfterReconnect() {
        val oldChat = ChatConnectionRecovery()
        val visibleChat = ChatConnectionRecovery()
        for (recovery in listOf(oldChat, visibleChat)) {
            recovery.watchStarted(connected = false)
            recovery.onConnection(ConnectionStatus.CONNECTED, true)
            recovery.onConnection(ConnectionStatus.RECONNECTING, true)
        }
        val subscriptions = mutableListOf<String>()
        if (visibleChat.onConnection(ConnectionStatus.CONNECTED, true)) subscriptions += "tesy"
        if (oldChat.onConnection(ConnectionStatus.CONNECTED, false)) subscriptions += "ClaudeAssist"
        assertEquals(listOf("tesy"), subscriptions)
        assertTrue(oldChat.pending)
    }

    @Test fun aBackgroundReconnectKeepsRecoveryPendingUntilTheChatReturns() {
        val recovery = ChatConnectionRecovery()
        recovery.onConnection(ConnectionStatus.CONNECTED, true)
        recovery.onConnection(ConnectionStatus.OFFLINE, false)
        assertFalse(recovery.onConnection(ConnectionStatus.CONNECTED, false))
        assertTrue(recovery.pending)
        assertTrue(recovery.onConnection(ConnectionStatus.CONNECTED, true))
    }

    @Test fun aRefreshQueuedBeforeTheFirstConnectionIsNotLost() {
        val recovery = ChatConnectionRecovery()
        recovery.watchStarted(connected = false)
        recovery.pending = true
        assertTrue(recovery.onConnection(ConnectionStatus.CONNECTED, true))
    }

    @Test fun cancellingTheWatchCancelsPendingRecovery() {
        val recovery = ChatConnectionRecovery()
        recovery.onConnection(ConnectionStatus.CONNECTED, true)
        recovery.onConnection(ConnectionStatus.OFFLINE, true)
        recovery.pending = false
        assertFalse(recovery.onConnection(ConnectionStatus.CONNECTED, true))
    }
}
