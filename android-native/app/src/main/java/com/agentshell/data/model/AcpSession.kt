package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AcpSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val updatedAt: String,
    val provider: String? = null,
)
