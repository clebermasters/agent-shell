package com.agentshell.data.model

import kotlinx.serialization.Serializable

/** A completion is an inbox item, independent of the expiring live activity snapshot. */
@Serializable
data class AgentCompletionReceipt(
    val target: ChatTarget,
    val eventId: String,
    val finishedAt: Long,
    val acknowledged: Boolean = false,
) {
    fun asActivity() = AgentActivity(
        status = AgentActivityStatus.IDLE,
        detail = "Agent finished — open chat to acknowledge",
        source = "saved-completion",
        confidence = "reported",
        finishedAt = finishedAt,
        completionReason = "completed",
        unreadCompletion = true,
    )

    companion object {
        fun from(target: ChatTarget, activity: AgentActivity): AgentCompletionReceipt? {
            val finishedAt = activity.finishedAt ?: return null
            if (activity.completionReason != "completed") return null
            return AgentCompletionReceipt(target, "${activity.turnId.orEmpty()}:$finishedAt", finishedAt)
        }
    }
}
