package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CronJob(
    val id: String,
    val name: String,
    val command: String,
    val schedule: String,
    val enabled: Boolean,
    val lastRun: String? = null,
    val nextRun: String? = null,
    val output: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val emailTo: String? = null,
    val logOutput: Boolean = false,
    val tmuxSession: String? = null,
    val environment: Map<String, String>? = null,
    val workdir: String? = null,
    val prompt: String? = null,
    val llmProvider: String? = null,
    val llmModel: String? = null
)
