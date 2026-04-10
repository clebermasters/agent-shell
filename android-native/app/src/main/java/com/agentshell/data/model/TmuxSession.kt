package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TmuxSession(
    val name: String,
    val attached: Boolean,
    val windows: Int,
    val created: String? = null,
    val tool: String? = null,
)

@Serializable
data class TmuxWindow(
    val id: Int,
    val name: String,
    val active: Boolean,
    val panes: Int
)
