package com.agentshell.data.model

import kotlinx.serialization.Serializable

enum class AgentSignalKind { FINISHED, QUIET }
data class AgentSignal(val kind: AgentSignalKind, val durationSeconds: Long? = null)

/** Persist only transition receipts and active turns, so reconnects do not repeat sounds. */
@Serializable
data class AgentSignalLedger(
    val activeTurnId: String? = null,
    val activeStartedAt: Long? = null,
    val armed: Boolean = false,
    val lastFinishedId: String? = null,
    val lastFinishedAt: Long? = null,
    val quietArmed: Boolean = false,
    val lastQuietAt: Long? = null,
) {
    fun observe(activity: AgentActivity): Pair<AgentSignalLedger, AgentSignal?> {
        var next = this
        if (activity.status == AgentActivityStatus.WORKING) {
            val alreadyFinished = activity.turnId != null && "turn:${activity.turnId}" == lastFinishedId ||
                (activity.startedAt != null && lastFinishedAt != null && activity.startedAt <= lastFinishedAt)
            if (!alreadyFinished) next = next.copy(activeTurnId = activity.turnId, activeStartedAt = activity.startedAt, armed = true)
        }
        if (activity.source.startsWith("tmux") && activity.status in setOf(AgentActivityStatus.WORKING, AgentActivityStatus.RECENT_ACTIVITY)) {
            next = next.copy(quietArmed = true)
        }
        val finished = activity.finishedAt
        if (finished != null && activity.completionReason != null && activity.confidence == "reported") {
            val id = activity.turnId?.let { "turn:$it" } ?: "finished:$finished"
            if (id != next.lastFinishedId) {
                val sameTurn = next.activeTurnId == null || activity.turnId == null || next.activeTurnId == activity.turnId
                val followsStart = next.activeStartedAt == null || finished >= next.activeStartedAt
                val notify = next.armed && sameTurn && followsStart && activity.completionReason == "completed" && activity.status == AgentActivityStatus.IDLE
                val duration = next.activeStartedAt?.let { ((finished - it).coerceAtLeast(0)) / 1000 }
                next = next.copy(armed = false, lastFinishedId = id, lastFinishedAt = finished, quietArmed = false)
                return next to if (notify) AgentSignal(AgentSignalKind.FINISHED, duration) else null
            }
        }
        val quiet = activity.quietAt
        if (quiet != null && activity.source.startsWith("tmux") && activity.status in setOf(AgentActivityStatus.UNKNOWN, AgentActivityStatus.IDLE)) {
            val notify = next.quietArmed && quiet != next.lastQuietAt
            next = next.copy(quietArmed = false, lastQuietAt = quiet)
            return next to if (notify) AgentSignal(AgentSignalKind.QUIET) else null
        }
        return next to null
    }
}
