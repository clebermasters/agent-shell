package com.agentshell.feature.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.CronJob
import com.agentshell.data.repository.CronRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class CronUiState(
    val jobs: List<CronJob> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val testOutput: String? = null,
    val isTesting: Boolean = false,
)

@HiltViewModel
class CronViewModel @Inject constructor(
    private val repository: CronRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    init {
        observeEvents()
        requestJobs()
    }

    fun requestJobs() {
        _state.update { it.copy(isLoading = true, error = null) }
        repository.requestJobs()
    }

    fun createJob(job: CronJob) {
        repository.createJob(job)
    }

    fun updateJob(job: CronJob) {
        repository.updateJob(job)
    }

    fun deleteJob(id: String) {
        repository.deleteJob(id)
        // Optimistically remove from list
        _state.update { it.copy(jobs = it.jobs.filter { j -> j.id != id }) }
    }

    fun toggleJob(id: String) {
        val newEnabled = !(_state.value.jobs.firstOrNull { it.id == id }?.enabled ?: true)
        repository.toggleJob(id, newEnabled)
        // Optimistically update
        _state.update { s ->
            s.copy(jobs = s.jobs.map { j ->
                if (j.id == id) j.copy(enabled = newEnabled) else j
            })
        }
    }

    fun testCommand(command: String) {
        _state.update { it.copy(isTesting = true, testOutput = null) }
        repository.testCommand(command)
    }

    fun clearTestOutput() {
        _state.update { it.copy(testOutput = null) }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            repository.filteredCronEvents().collect { msg ->
                when (msg["type"] as? String) {
                    "cron-jobs-list" -> handleJobsList(msg)
                    "cron-job-created" -> handleJobCreated(msg)
                    "cron-job-updated" -> handleJobUpdated(msg)
                    "cron-job-deleted" -> handleJobDeleted(msg)
                    "cron-command-output" -> handleCommandOutput(msg)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleJobsList(msg: Map<String, Any?>) {
        val rawJobs = msg["jobs"] as? List<*> ?: return
        val jobs = rawJobs.mapNotNull { parseJob(it as? Map<String, Any?>) }
        _state.update { it.copy(jobs = jobs, isLoading = false) }
    }

    private fun handleJobCreated(msg: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val job = parseJob(msg["job"] as? Map<String, Any?>) ?: return
        _state.update { s ->
            s.copy(jobs = s.jobs.filterNot { it.id == job.id } + job)
        }
    }

    private fun handleJobUpdated(msg: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val job = parseJob(msg["job"] as? Map<String, Any?>) ?: return
        _state.update { s ->
            s.copy(jobs = s.jobs.map { if (it.id == job.id) job else it })
        }
    }

    private fun handleJobDeleted(msg: Map<String, Any?>) {
        val id = msg["id"] as? String ?: return
        _state.update { s -> s.copy(jobs = s.jobs.filter { it.id != id }) }
    }

    private fun handleCommandOutput(msg: Map<String, Any?>) {
        val output = msg["output"] as? String ?: ""
        val error = msg["error"] as? String
        _state.update { it.copy(testOutput = error ?: output, isTesting = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJob(map: Map<String, Any?>?): CronJob? {
        val m = map ?: return null
        val id = m["id"] as? String ?: return null
        val name = m["name"] as? String ?: return null
        val command = m["command"] as? String ?: return null
        val schedule = m["schedule"] as? String ?: return null
        val enabled = m["enabled"] as? Boolean ?: true
        val env = (m["environment"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { key -> (v as? String)?.let { value -> key to value } }
        }?.toMap()
        return CronJob(
            id = id,
            name = name,
            command = command,
            schedule = schedule,
            enabled = enabled,
            lastRun = m["lastRun"] as? String,
            nextRun = m["nextRun"] as? String,
            output = m["output"] as? String,
            createdAt = m["createdAt"] as? String,
            updatedAt = m["updatedAt"] as? String,
            emailTo = m["emailTo"] as? String,
            logOutput = m["logOutput"] as? Boolean ?: false,
            tmuxSession = m["tmuxSession"] as? String,
            environment = env,
            workdir = m["workdir"] as? String,
            prompt = m["prompt"] as? String,
            llmProvider = m["llmProvider"] as? String,
            llmModel = m["llmModel"] as? String,
        )
    }
}
