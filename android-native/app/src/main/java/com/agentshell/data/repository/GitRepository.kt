package com.agentshell.data.repository

import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.gitAmend
import com.agentshell.data.remote.gitBlame
import com.agentshell.data.remote.gitBranches
import com.agentshell.data.remote.gitCheckout
import com.agentshell.data.remote.gitCherryPick
import com.agentshell.data.remote.gitCommit
import com.agentshell.data.remote.gitCommitDiff
import com.agentshell.data.remote.gitCommitFiles
import com.agentshell.data.remote.gitCompare
import com.agentshell.data.remote.gitCreateBranch
import com.agentshell.data.remote.gitCreateTag
import com.agentshell.data.remote.gitDeleteBranch
import com.agentshell.data.remote.gitDeleteTag
import com.agentshell.data.remote.gitDiff
import com.agentshell.data.remote.gitFileHistory
import com.agentshell.data.remote.gitListTags
import com.agentshell.data.remote.gitLog
import com.agentshell.data.remote.gitMerge
import com.agentshell.data.remote.gitPull
import com.agentshell.data.remote.gitPush
import com.agentshell.data.remote.gitRepoInfo
import com.agentshell.data.remote.gitResolveConflict
import com.agentshell.data.remote.gitRevert
import com.agentshell.data.remote.gitSearch
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
            "git-commit-files-result",
            "git-search-result",
            "git-file-history-result",
            "git-blame-result",
            "git-compare-result",
            "git-repo-info-result",
            "git-tags-result",
            "git-stash-list-result",
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

    fun requestDiff(sessionName: String? = null, path: String? = null, filePath: String? = null, staged: Boolean = false) {
        wsService.gitDiff(sessionName, path, filePath, staged)
    }

    fun requestLog(sessionName: String? = null, path: String? = null, limit: Int = 50, offset: Int = 0) {
        wsService.gitLog(sessionName, path, limit, offset)
    }

    fun requestBranches(sessionName: String? = null, path: String? = null) {
        wsService.gitBranches(sessionName, path)
    }

    fun requestCommitFiles(commitHash: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCommitFiles(sessionName, path, commitHash)
    }

    fun requestCommitDiff(commitHash: String, filePath: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCommitDiff(sessionName, path, commitHash, filePath)
    }

    fun search(query: String, author: String? = null, since: String? = null, limit: Int = 50, sessionName: String? = null, path: String? = null) {
        wsService.gitSearch(sessionName, path, query, author, since, limit)
    }

    fun requestFileHistory(filePath: String, limit: Int = 50, offset: Int = 0, sessionName: String? = null, path: String? = null) {
        wsService.gitFileHistory(sessionName, path, filePath, limit, offset)
    }

    fun requestBlame(filePath: String, sessionName: String? = null, path: String? = null) {
        wsService.gitBlame(sessionName, path, filePath)
    }

    fun requestCompare(baseBranch: String, compareBranch: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCompare(sessionName, path, baseBranch, compareBranch)
    }

    fun requestRepoInfo(sessionName: String? = null, path: String? = null) {
        wsService.gitRepoInfo(sessionName, path)
    }

    fun requestTags(sessionName: String? = null, path: String? = null) {
        wsService.gitListTags(sessionName, path)
    }

    fun requestStashList(sessionName: String? = null, path: String? = null) {
        wsService.gitStash(sessionName, path, "list")
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

    fun cherryPick(commitHash: String, sessionName: String? = null, path: String? = null) {
        wsService.gitCherryPick(sessionName, path, commitHash)
    }

    fun revert(commitHash: String, sessionName: String? = null, path: String? = null) {
        wsService.gitRevert(sessionName, path, commitHash)
    }

    fun merge(branch: String, sessionName: String? = null, path: String? = null) {
        wsService.gitMerge(sessionName, path, branch)
    }

    fun amend(message: String? = null, sessionName: String? = null, path: String? = null) {
        wsService.gitAmend(sessionName, path, message)
    }

    fun createTag(name: String, commitHash: String? = null, message: String? = null, sessionName: String? = null, path: String? = null) {
        wsService.gitCreateTag(sessionName, path, name, commitHash, message)
    }

    fun deleteTag(name: String, sessionName: String? = null, path: String? = null) {
        wsService.gitDeleteTag(sessionName, path, name)
    }

    fun resolveConflict(filePath: String, resolution: String, sessionName: String? = null, path: String? = null) {
        wsService.gitResolveConflict(sessionName, path, filePath, resolution)
    }
}
