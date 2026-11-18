package com.agentshell.data.model

import kotlinx.serialization.Serializable

/** Each browser host remembers one paired chat and an independent split layout. */
@Serializable
data class DeploymentPreferences(
    val hostId: String,
    val chat: ChatTarget? = null,
    val layout: ChatSplitPreferences = ChatSplitPreferences(),
) {
    fun withChat(target: ChatTarget): DeploymentPreferences {
        require(target.hostId == hostId && target.sessionName.isNotBlank() && target.windowIndex >= 0)
        return copy(chat = target, layout = layout.copy(enabled = true))
    }

    internal fun normalized(): DeploymentPreferences {
        val selected = chat?.takeIf { it.hostId == hostId && it.sessionName.isNotBlank() && it.windowIndex >= 0 }
        return copy(chat = selected, layout = layout.normalized().copy(enabled = selected != null && layout.enabled))
    }
}
