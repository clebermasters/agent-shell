package com.agentshell.data.model

data class UsageWindow(
    val label: String,
    val utilization: Double,
    val resetsAt: String? = null,
    val windowDurationMins: Long? = null,
)

data class AiUsage(
    val provider: String,
    val primary: UsageWindow? = null,
    val secondary: UsageWindow? = null,
    val planType: String? = null,
    val limitId: String? = null,
    val limitName: String? = null,
    val error: String? = null,
)
