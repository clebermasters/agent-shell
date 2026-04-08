package com.agentshell.data.repository

import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.gitBranches
import com.agentshell.data.remote.gitCheckout
import com.agentshell.data.remote.gitCommit
import com.agentshell.data.remote.gitCreateBranch
import com.agentshell.data.remote.gitDeleteBranch
import com.agentshell.data.remote.gitDiff
import com.agentshell.data.remote.gitLog
import com.agentshell.data.remote.gitPull
import com.agentshell.data.remote.gitPush
import com.agentshell.data.remote.gitStage
import com.agentshell.data.remote.gitStash
import com.agentshell.data.remote.gitStatus
import com.agentshell.data.remote.gitUnstage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitRepository @Inject constructor(
    private val wsService: WebSocketService,
) {
    companion object {
        private val GIT_TYPES = setOf(
            "git-status-result",
            "git-diff-result",
            "git-log-result",
            "git-branches-result",
            "git-graph-result",
            "git-operation-result",
            "error",
        )
    }

    fun filteredGitEvents(): Flow<Map<String, Any?>> =
        wsService.messages.filter { msg ->
            GIT_TYPES.contains(msg["type"] as? String)
        }

    // -- Query commands --

    fun requestStatus(sessionName: String? = null, path: String? = null) {
        wsService.gitStatus(sessionName, path)
    }

    fun requestDiff(
        sessionName: String? = null,
        path: String? = null,
        filePath: String? = null,
        staged: Boolean = false,
    ) {
        wsService.gitDiff(sessionName, path, filePath, staged)
    }

    fun requestLog(
        sessionName: String? = null,
        path: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ) {
        wsService.gitLog(sessionName, path, limit, offset)
    }

    fun requestBranches(sessionName: String? = null, path: String? = null) {
        wsService.gitBranches(sessionName, path)
    }

    // -- Mutation commands --

    fun checkout(branch: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCheckout(sessionName, path, branch)
    }

    fun createBranch(branch: String, startPoint: String? = null, sessionName: String? = null, path: String? = null) {
        wsService.gitCreateBranch(sessionName, path, branch, startPoint)
    }

    fun deleteBranch(branch: String, sessionName: String? = null, path: String? = null) {
        wsService.gitDeleteBranch(sessionName, path, branch)
    }

    fun stageFiles(files: List<String>, sessionName: String? = null, path: String? = null) {
        wsService.gitStage(sessionName, path, files)
    }

    fun unstageFiles(files: List<String>, sessionName: String? = null, path: String? = null) {
        wsService.gitUnstage(sessionName, path, files)
    }

    fun commit(message: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCommit(sessionName, path, message)
    }

    fun push(remote: String? = null, branch: String? = null, sessionName: String? = null, path: String? = null) {
        wsService.gitPush(sessionName, path, remote, branch)
    }

    fun pull(sessionName: String? = null, path: String? = null) {
        wsService.gitPull(sessionName, path)
    }

    fun stash(action: String, sessionName: String? = null, path: String? = null) {
        wsService.gitStash(sessionName, path, action)
    }
}
