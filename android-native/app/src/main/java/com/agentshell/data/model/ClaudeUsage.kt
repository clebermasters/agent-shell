package com.agentshell.data.model

data class UsageBucket(
    val utilization: Double,
    val resetsAt: String,
)

data class ClaudeUsage(
    val fiveHour: UsageBucket?,
    val sevenDay: UsageBucket?,
    val sevenDaySonnet: UsageBucket?,
    val error: String? = null,
)
