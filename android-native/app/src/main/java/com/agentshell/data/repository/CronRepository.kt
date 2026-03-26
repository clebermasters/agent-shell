package com.agentshell.data.repository

import com.agentshell.data.model.CronJob
import com.agentshell.data.remote.WebSocketService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CronRepository @Inject constructor(
    private val wsService: WebSocketService,
) {
    companion object {
        private val CRON_TYPES = setOf(
            "cron-jobs-list",
            "cron-job-created",
            "cron-job-updated",
            "cron-job-deleted",
            "cron-command-output",
        )
    }

    /** Filtered stream of cron-related WebSocket messages. */
    val cronEvents: SharedFlow<Map<String, Any?>>
        get() = wsService.messages as SharedFlow<Map<String, Any?>>

    fun filteredCronEvents() = wsService.messages.filter { msg ->
        CRON_TYPES.contains(msg["type"] as? String)
    }

    fun requestJobs() {
        wsService.send(mapOf("type" to "list-cron-jobs"))
    }

    fun createJob(job: CronJob) {
        wsService.send(buildJobPayload("create-cron-job", job))
    }

    fun updateJob(job: CronJob) {
        wsService.send(buildJobPayload("update-cron-job", job))
    }

    fun deleteJob(id: String) {
        wsService.send(mapOf("type" to "delete-cron-job", "id" to id))
    }

    fun toggleJob(id: String) {
        wsService.send(mapOf("type" to "toggle-cron-job", "id" to id))
    }

    fun testCommand(command: String, workdir: String? = null) {
        val payload = mutableMapOf<String, Any?>("type" to "test-cron-command", "command" to command)
        if (workdir != null) payload["workdir"] = workdir
        wsService.send(payload)
    }

    private fun buildJobPayload(type: String, job: CronJob): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "type" to type,
            "id" to job.id,
            "name" to job.name,
            "command" to job.command,
            "schedule" to job.schedule,
            "enabled" to job.enabled,
            "logOutput" to job.logOutput,
        )
        job.emailTo?.let { payload["emailTo"] = it }
        job.tmuxSession?.let { payload["tmuxSession"] = it }
        job.workdir?.let { payload["workdir"] = it }
        job.prompt?.let { payload["prompt"] = it }
        job.llmProvider?.let { payload["llmProvider"] = it }
        job.llmModel?.let { payload["llmModel"] = it }
        job.environment?.let { payload["environment"] = it }
        return payload
    }
}
