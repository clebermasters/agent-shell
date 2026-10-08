package com.agentshell.data.model

enum class AgentActivityStatus { WORKING, RECENT_ACTIVITY, WAITING, IDLE, FAILED, UNKNOWN }

/** Runtime state is not persisted: a saved busy flag is not a live observation. */
data class AgentActivity(
    val status: AgentActivityStatus = AgentActivityStatus.UNKNOWN,
    val detail: String = "Checking agent status",
    val source: String = "none",
    val confidence: String = "unknown",
    val startedAt: Long? = null,
    val observedAt: Long = 0,
    val receivedAt: Long = 0,
    val observerId: String = "",
    val sequence: Long = 0,
    val paneId: String? = null,
    val backendSnapshot: Boolean = false,
    val turnId: String? = null,
    val lastActivityAt: Long? = null,
    val finishedAt: Long? = null,
    val completionReason: String? = null,
    val quietAt: Long? = null,
) {
    fun expired(now: Long): Boolean = receivedAt > 0 && now - receivedAt > 15_000
    fun unknown(reason: String) = copy(status = AgentActivityStatus.UNKNOWN, detail = reason, startedAt = null, source = "none", confidence = "unknown", quietAt = null)
    fun elapsedSeconds(now: Long): Long? = startedAt?.let {
        ((observedAt - it).coerceAtLeast(0) + (now - receivedAt).coerceAtLeast(0)) / 1000
    }
    fun accepts(next: AgentActivity): Boolean = when {
        !backendSnapshot -> true
        observerId == next.observerId -> next.sequence > sequence || (next.sequence == sequence && next.observedAt >= observedAt)
        else -> next.observedAt >= observedAt
    }
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(message: Map<String, Any?>, receivedAt: Long): AgentActivity? {
            val state = message["state"] as? Map<String, Any?> ?: return null
            val rawStatus = state["status"] as? String ?: return null
            val status = AgentActivityStatus.entries.firstOrNull { it.name.equals(rawStatus.replace('-', '_'), true) } ?: AgentActivityStatus.UNKNOWN
            return AgentActivity(
                status = status, detail = (state["detail"] as? String)?.take(200) ?: "Agent status is not available",
                source = state["source"] as? String ?: "none", confidence = state["confidence"] as? String ?: "unknown",
                startedAt = (state["startedAt"] as? Number)?.toLong(), observedAt = (state["observedAt"] as? Number)?.toLong() ?: 0,
                receivedAt = receivedAt, observerId = state["observerId"] as? String ?: "",
                sequence = (state["sequence"] as? Number)?.toLong() ?: 0, paneId = message["paneId"] as? String,
                backendSnapshot = true,
                turnId = state["turnId"] as? String,
                lastActivityAt = (state["lastActivityAt"] as? Number)?.toLong(),
                finishedAt = (state["finishedAt"] as? Number)?.toLong(),
                completionReason = state["completionReason"] as? String,
                quietAt = (state["quietAt"] as? Number)?.toLong(),
            )
        }
    }
}
