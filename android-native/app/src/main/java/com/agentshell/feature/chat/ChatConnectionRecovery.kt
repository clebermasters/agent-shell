package com.agentshell.feature.chat

import com.agentshell.data.model.ConnectionStatus

/** Keeps a chat's pending reconnect request separate from its screen lifecycle. */
internal class ChatConnectionRecovery {
    private var hasSeenConnection = false
    var pending = false

    fun watchStarted(connected: Boolean) {
        pending = false
        hasSeenConnection = hasSeenConnection || connected
    }

    fun onConnection(status: ConnectionStatus, screenActive: Boolean): Boolean {
        when (status) {
            ConnectionStatus.CONNECTED -> {
                hasSeenConnection = true
                // Back-stack ViewModels share the socket. Only the visible chat
                // may restore its watch; hidden chats retain their pending flag.
                return pending && screenActive
            }
            ConnectionStatus.RECONNECTING, ConnectionStatus.OFFLINE -> {
                if (hasSeenConnection) pending = true
            }
            ConnectionStatus.CONNECTING -> Unit
        }
        return false
    }
}
