package com.agentshell.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.GitBlameLine
import com.agentshell.data.model.GitBranchInfo
import com.agentshell.data.model.GitCommitInfo
import com.agentshell.data.model.GitCompareResult
import com.agentshell.data.model.GitDiffData
import com.agentshell.data.model.GitFileChange
import com.agentshell.data.model.GitGraphNode
import com.agentshell.data.model.GitOperationResult
import com.agentshell.data.model.GitRemoteInfo
import com.agentshell.data.model.GitRepoInfo
import com.agentshell.data.model.GitStashEntry
import com.agentshell.data.model.GitStatusData
import com.agentshell.data.model.GitTagInfo
import com.agentshell.data.model.LaneConnection
import com.agentshell.data.model.LaneInfo
import com.agentshell.data.repository.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GitUiState(
    // Current context
    val sessionName: String? = null,
    val path: String? = null,
    val isAcp: Boolean = false,
    val repoName: String = "",

    // Tab
    val selectedTab: GitTab = GitTab.STATUS,

    // Status
    val status: GitStatusData = GitStatusData(),
    val isLoadingStatus: Boolean = false,

    // Branches
    val branches: List<GitBranchInfo> = emptyList(),
    val isLoadingBranches: Boolean = false,

    // Commit log / graph
    val commits: List<GitCommitInfo> = emptyList(),
    val graphNodes: List<GitGraphNode> = emptyList(),
    val isLoadingLog: Boolean = false,
    val hasMoreCommits: Boolean = false,
    val logOffset: Int = 0,

    // Diff viewer
    val currentDiff: GitDiffData? = null,
    val isLoadingDiff: Boolean = false,
    val showDiffSheet: Boolean = false,
    val diffIsStaged: Boolean = false,

    // Commit form
    val showCommitSheet: Boolean = false,
    val commitMessage: String = "",
    val selectedFiles: Set<String> = emptySet(),
    val isCommitting: Boolean = false,

    // Commit detail
    val showCommitDetailSheet: Boolean = false,
    val selectedCommit: GitCommitInfo? = null,
    val commitFiles: List<GitFileChange> = emptyList(),
    val isLoadingCommitFiles: Boolean = false,

    // Operations
    val operationResult: GitOperationResult? = null,
    val error: String? = null,

    // Search
    val searchQuery: String = "",
    val searchResults: List<GitCommitInfo> = emptyList(),
    val isSearching: Boolean = false,
    val showSearchBar: Boolean = false,

    // File history
    val fileHistoryPath: String? = null,
    val fileHistory: List<GitCommitInfo> = emptyList(),
    val isLoadingFileHistory: Boolean = false,
    val showFileHistorySheet: Boolean = false,

    // Blame
    val blameLines: List<GitBlameLine> = emptyList(),
    val blamePath: String? = null,
    val isLoadingBlame: Boolean = false,
    val showBlameSheet: Boolean = false,

    // Compare
    val compareResult: GitCompareResult? = null,
    val isLoadingCompare: Boolean = false,
    val showCompareSheet: Boolean = false,

    // Repo info
    val repoInfo: GitRepoInfo? = null,
    val isLoadingRepoInfo: Boolean = false,
    val showRepoInfoSheet: Boolean = false,

    // Tags
    val tags: List<GitTagInfo> = emptyList(),
    val isLoadingTags: Boolean = false,

    // Stash list
    val stashEntries: List<GitStashEntry> = emptyList(),
    val isLoadingStash: Boolean = false,
    val showStashSheet: Boolean = false,

    // Auto-refresh
    val autoRefreshEnabled: Boolean = false,
)

enum class GitTab { STATUS, BRANCHES, GRAPH }

@HiltViewModel
class GitViewModel @Inject constructor(
    private val repository: GitRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    init {
        observeEvents()
    }

    fun initialize(sessionName: String?, path: String?, isAcp: Boolean) {
        // Normalize: empty/blank path → null so backend resolves from tmux session
        val cleanPath = path?.takeIf { it.isNotBlank() }
        // For tmux: use sessionName, path=null → backend calls tmux::get_session_path
        // For ACP: use path (cwd), sessionName=null → backend uses path directly
        val effectiveSession = if (isAcp) null else sessionName
        val effectivePath = if (isAcp) cleanPath else null
        val repoName = (cleanPath ?: sessionName ?: "").substringAfterLast('/')
        _state.update {
            it.copy(
                sessionName = effectiveSession,
                path = effectivePath,
                isAcp = isAcp,
                repoName = repoName,
            )
        }
        refreshAll()
    }

    fun selectTab(tab: GitTab) {
        _state.update { it.copy(selectedTab = tab) }
        when (tab) {
            GitTab.STATUS -> refreshStatus()
            GitTab.BRANCHES -> refreshBranches()
            GitTab.GRAPH -> if (_state.value.commits.isEmpty()) refreshLog()
        }
    }

    // -- Refresh --

    fun refreshAll() {
        refreshStatus()
        refreshBranches()
    }

    fun refreshStatus() {
        _state.update { it.copy(isLoadingStatus = true, error = null) }
        val s = _state.value
        repository.requestStatus(s.sessionName, s.path)
    }

    fun refreshBranches() {
        _state.update { it.copy(isLoadingBranches = true) }
        val s = _state.value
        repository.requestBranches(s.sessionName, s.path)
    }

    fun refreshLog() {
        _state.update { it.copy(isLoadingLog = true, logOffset = 0) }
        val s = _state.value
        repository.requestLog(s.sessionName, s.path, limit = 50, offset = 0)
    }

    fun loadMoreCommits() {
        if (_state.value.isLoadingLog || !_state.value.hasMoreCommits) return
        _state.update { it.copy(isLoadingLog = true) }
        val s = _state.value
        repository.requestLog(s.sessionName, s.path, limit = 50, offset = s.logOffset)
    }

    // -- File operations --

    fun viewDiff(filePath: String, staged: Boolean) {
        _state.update { it.copy(isLoadingDiff = true, showDiffSheet = true, diffIsStaged = staged) }
        val s = _state.value
        repository.requestDiff(s.sessionName, s.path, filePath, staged)
    }

    fun closeDiffSheet() {
        _state.update { it.copy(showDiffSheet = false, currentDiff = null) }
    }

    fun stageFile(filePath: String) {
        val s = _state.value
        repository.stageFiles(listOf(filePath), s.sessionName, s.path)
        // Refresh status after a short delay to see changes
        refreshStatus()
    }

    fun unstageFile(filePath: String) {
        val s = _state.value
        repository.unstageFiles(listOf(filePath), s.sessionName, s.path)
        refreshStatus()
    }

    fun stageAll() {
        val s = _state.value
        val allFiles = s.status.modified.map { it.path } + s.status.untracked
        if (allFiles.isNotEmpty()) {
            repository.stageFiles(allFiles, s.sessionName, s.path)
            refreshStatus()
        }
    }

    fun unstageAll() {
        val s = _state.value
        val allStaged = s.status.staged.map { it.path }
        if (allStaged.isNotEmpty()) {
            repository.unstageFiles(allStaged, s.sessionName, s.path)
            refreshStatus()
        }
    }

    // -- Commit --

    fun showCommitForm() {
        val stagedPaths = _state.value.status.staged.map { it.path }.toSet()
        _state.update { it.copy(showCommitSheet = true, selectedFiles = stagedPaths, commitMessage = "") }
    }

    fun hideCommitForm() {
        _state.update { it.copy(showCommitSheet = false) }
    }

    fun updateCommitMessage(msg: String) {
        _state.update { it.copy(commitMessage = msg) }
    }

    fun toggleFileSelection(path: String) {
        _state.update { state ->
            val newSet = state.selectedFiles.toMutableSet()
            if (newSet.contains(path)) newSet.remove(path) else newSet.add(path)
            state.copy(selectedFiles = newSet)
        }
    }

    fun commit() {
        val s = _state.value
        if (s.commitMessage.isBlank()) return
        _state.update { it.copy(isCommitting = true) }

        // Stage only the selected files first, then commit
        val filesToStage = s.selectedFiles.toList()
        if (filesToStage.isNotEmpty()) {
            repository.stageFiles(filesToStage, s.sessionName, s.path)
        }
        repository.commit(s.commitMessage, s.sessionName, s.path)
    }

    fun push() {
        val s = _state.value
        repository.push(sessionName = s.sessionName, path = s.path)
    }

    fun pull() {
        val s = _state.value
        repository.pull(s.sessionName, s.path)
    }

    // -- Branches --

    fun checkoutBranch(branch: String) {
        val s = _state.value
        repository.checkout(branch, s.sessionName, s.path)
    }

    fun createBranch(name: String) {
        val s = _state.value
        repository.createBranch(name, sessionName = s.sessionName, path = s.path)
    }

    fun deleteBranch(name: String) {
        val s = _state.value
        repository.deleteBranch(name, s.sessionName, s.path)
    }

    // -- Stash (push/pop) --

    fun stashPush() {
        val s = _state.value
        repository.stash("push", s.sessionName, s.path)
    }

    fun stashPop() {
        val s = _state.value
        repository.stash("pop", s.sessionName, s.path)
    }

    // -- Commit detail --

    fun showCommitDetail(commit: GitCommitInfo) {
        _state.update { it.copy(showCommitDetailSheet = true, selectedCommit = commit, commitFiles = emptyList(), isLoadingCommitFiles = true) }
        val s = _state.value
        repository.requestCommitFiles(commit.hash, s.sessionName, s.path)
    }

    fun hideCommitDetail() {
        _state.update { it.copy(showCommitDetailSheet = false, selectedCommit = null, commitFiles = emptyList()) }
    }

    fun viewCommitFileDiff(commitHash: String, filePath: String) {
        _state.update { it.copy(isLoadingDiff = true, showDiffSheet = true, diffIsStaged = false) }
        val s = _state.value
        repository.requestCommitDiff(commitHash, filePath, s.sessionName, s.path)
    }

    // -- Search --

    fun toggleSearchBar() {
        _state.update { it.copy(showSearchBar = !it.showSearchBar, searchQuery = "", searchResults = emptyList()) }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun performSearch() {
        val s = _state.value
        if (s.searchQuery.isBlank()) return
        _state.update { it.copy(isSearching = true) }
        repository.search(s.searchQuery, sessionName = s.sessionName, path = s.path)
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    // -- File history --

    fun showFileHistory(filePath: String) {
        _state.update { it.copy(showFileHistorySheet = true, fileHistoryPath = filePath, fileHistory = emptyList(), isLoadingFileHistory = true) }
        val s = _state.value
        repository.requestFileHistory(filePath, sessionName = s.sessionName, path = s.path)
    }

    fun hideFileHistory() {
        _state.update { it.copy(showFileHistorySheet = false, fileHistoryPath = null, fileHistory = emptyList()) }
    }

    // -- Cherry-pick & Revert --

    fun cherryPick(commitHash: String) {
        val s = _state.value
        repository.cherryPick(commitHash, s.sessionName, s.path)
    }

    fun revertCommit(commitHash: String) {
        val s = _state.value
        repository.revert(commitHash, s.sessionName, s.path)
    }

    // -- Merge --

    fun mergeBranch(branch: String) {
        val s = _state.value
        repository.merge(branch, s.sessionName, s.path)
    }

    // -- Blame --

    fun showBlame(filePath: String) {
        _state.update { it.copy(showBlameSheet = true, blamePath = filePath, blameLines = emptyList(), isLoadingBlame = true) }
        val s = _state.value
        repository.requestBlame(filePath, s.sessionName, s.path)
    }

    fun hideBlame() {
        _state.update { it.copy(showBlameSheet = false, blamePath = null, blameLines = emptyList()) }
    }

    // -- Compare --

    fun showCompare(baseBranch: String, compareBranch: String) {
        _state.update { it.copy(showCompareSheet = true, compareResult = null, isLoadingCompare = true) }
        val s = _state.value
        repository.requestCompare(baseBranch, compareBranch, s.sessionName, s.path)
    }

    fun hideCompare() {
        _state.update { it.copy(showCompareSheet = false, compareResult = null) }
    }

    // -- Repo info --

    fun showRepoInfo() {
        _state.update { it.copy(showRepoInfoSheet = true, repoInfo = null, isLoadingRepoInfo = true) }
        val s = _state.value
        repository.requestRepoInfo(s.sessionName, s.path)
    }

    fun hideRepoInfo() {
        _state.update { it.copy(showRepoInfoSheet = false) }
    }

    // -- Amend --

    fun amendCommit(message: String? = null) {
        val s = _state.value
        repository.amend(message, s.sessionName, s.path)
    }

    // -- Tags --

    fun refreshTags() {
        _state.update { it.copy(isLoadingTags = true) }
        val s = _state.value
        repository.requestTags(s.sessionName, s.path)
    }

    fun createTag(name: String, commitHash: String? = null, message: String? = null) {
        val s = _state.value
        repository.createTag(name, commitHash, message, s.sessionName, s.path)
    }

    fun deleteTag(name: String) {
        val s = _state.value
        repository.deleteTag(name, s.sessionName, s.path)
    }

    // -- Stash list --

    fun showStashList() {
        _state.update { it.copy(showStashSheet = true, stashEntries = emptyList(), isLoadingStash = true) }
        val s = _state.value
        repository.requestStashList(s.sessionName, s.path)
    }

    fun hideStashList() {
        _state.update { it.copy(showStashSheet = false) }
    }

    fun stashApply(index: Int) {
        val s = _state.value
        repository.stash("apply stash@{$index}", s.sessionName, s.path)
    }

    fun stashDrop(index: Int) {
        val s = _state.value
        repository.stash("drop stash@{$index}", s.sessionName, s.path)
    }

    // -- Conflict resolution --

    fun resolveConflict(filePath: String, resolution: String) {
        val s = _state.value
        repository.resolveConflict(filePath, resolution, s.sessionName, s.path)
    }

    // -- Auto-refresh --

    fun toggleAutoRefresh() {
        val wasEnabled = _state.value.autoRefreshEnabled
        _state.update { it.copy(autoRefreshEnabled = !wasEnabled) }
        if (!wasEnabled) {
            viewModelScope.launch {
                while (_state.value.autoRefreshEnabled) {
                    delay(5000)
                    refreshStatus()
                }
            }
        }
    }

    // -- Clear messages --

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearOperationResult() {
        _state.update { it.copy(operationResult = null) }
    }

    // -------------------------------------------------------------------------
    // WebSocket event handling
    // -------------------------------------------------------------------------

    private fun observeEvents() {
        viewModelScope.launch {
            repository.filteredGitEvents().collect { msg ->
                when (msg["type"] as? String) {
                    "git-status-result" -> handleStatusResult(msg)
                    "git-diff-result" -> handleDiffResult(msg)
                    "git-log-result" -> handleLogResult(msg)
                    "git-branches-result" -> handleBranchesResult(msg)
                    "git-commit-files-result" -> handleCommitFilesResult(msg)
                    "git-operation-result" -> handleOperationResult(msg)
                    "git-search-result" -> handleSearchResult(msg)
                    "git-file-history-result" -> handleFileHistoryResult(msg)
                    "git-blame-result" -> handleBlameResult(msg)
                    "git-compare-result" -> handleCompareResult(msg)
                    "git-repo-info-result" -> handleRepoInfoResult(msg)
                    "git-tags-result" -> handleTagsResult(msg)
                    "git-stash-list-result" -> handleStashListResult(msg)
                    "error" -> {
                        val message = msg["message"] as? String ?: "Unknown error"
                        _state.update { it.copy(error = message, isLoadingStatus = false, isLoadingDiff = false, isLoadingLog = false, isLoadingBranches = false, isSearching = false, isLoadingFileHistory = false, isLoadingBlame = false, isLoadingCompare = false, isLoadingRepoInfo = false, isLoadingTags = false, isLoadingStash = false) }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleStatusResult(msg: Map<String, Any?>) {
        val branch = msg["branch"] as? String ?: ""
        val upstream = msg["upstream"] as? String
        val ahead = (msg["ahead"] as? Number)?.toInt() ?: 0
        val behind = (msg["behind"] as? Number)?.toInt() ?: 0

        val staged = (msg["staged"] as? List<Map<String, Any?>>)?.map { parseFileChange(it) } ?: emptyList()
        val modified = (msg["modified"] as? List<Map<String, Any?>>)?.map { parseFileChange(it) } ?: emptyList()
        val untracked = (msg["untracked"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val conflicts = (msg["conflicts"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        _state.update {
            it.copy(
                status = GitStatusData(branch, upstream, ahead, behind, staged, modified, untracked, conflicts),
                isLoadingStatus = false,
            )
        }
    }

    private fun parseFileChange(map: Map<String, Any?>): GitFileChange {
        return GitFileChange(
            path = map["path"] as? String ?: "",
            status = map["status"] as? String ?: "M",
            additions = (map["additions"] as? Number)?.toInt(),
            deletions = (map["deletions"] as? Number)?.toInt(),
        )
    }

    private fun handleDiffResult(msg: Map<String, Any?>) {
        _state.update {
            it.copy(
                currentDiff = GitDiffData(
                    filePath = msg["filePath"] as? String ?: "",
                    diff = msg["diff"] as? String ?: "",
                    additions = (msg["additions"] as? Number)?.toInt() ?: 0,
                    deletions = (msg["deletions"] as? Number)?.toInt() ?: 0,
                ),
                isLoadingDiff = false,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleLogResult(msg: Map<String, Any?>) {
        val commits = (msg["commits"] as? List<Map<String, Any?>>)?.map { parseCommit(it) } ?: emptyList()
        val hasMore = msg["hasMore"] as? Boolean ?: false

        _state.update { state ->
            val newCommits = if (state.logOffset == 0) commits else state.commits + commits
            val nodes = computeGraphLayout(newCommits)
            state.copy(
                commits = newCommits,
                graphNodes = nodes,
                hasMoreCommits = hasMore,
                logOffset = state.logOffset + commits.size,
                isLoadingLog = false,
            )
        }
    }

    private fun parseCommit(map: Map<String, Any?>): GitCommitInfo {
        return GitCommitInfo(
            hash = map["hash"] as? String ?: "",
            shortHash = map["shortHash"] as? String ?: "",
            message = map["message"] as? String ?: "",
            author = map["author"] as? String ?: "",
            date = map["date"] as? String ?: "",
            parents = (map["parents"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            refs = (map["refs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleBranchesResult(msg: Map<String, Any?>) {
        val branches = (msg["branches"] as? List<Map<String, Any?>>)?.map { b ->
            GitBranchInfo(
                name = b["name"] as? String ?: "",
                current = b["current"] as? Boolean ?: false,
                tracking = b["tracking"] as? String,
                ahead = (b["ahead"] as? Number)?.toInt(),
                behind = (b["behind"] as? Number)?.toInt(),
                lastCommit = b["lastCommit"] as? String,
            )
        } ?: emptyList()

        _state.update { it.copy(branches = branches, isLoadingBranches = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleCommitFilesResult(msg: Map<String, Any?>) {
        val files = (msg["files"] as? List<Map<String, Any?>>)?.map { parseFileChange(it) } ?: emptyList()
        _state.update { it.copy(commitFiles = files, isLoadingCommitFiles = false) }
    }

    private fun handleOperationResult(msg: Map<String, Any?>) {
        val result = GitOperationResult(
            operation = msg["operation"] as? String ?: "",
            success = msg["success"] as? Boolean ?: false,
            message = msg["message"] as? String,
            error = msg["error"] as? String,
        )

        _state.update {
            it.copy(
                operationResult = result,
                isCommitting = false,
                showCommitSheet = if (result.operation == "commit" && result.success) false else it.showCommitSheet,
            )
        }

        // Auto-refresh after successful mutations
        if (result.success) {
            when (result.operation) {
                "commit", "stage", "unstage", "pull", "stash" -> refreshStatus()
                "checkout", "create-branch", "delete-branch" -> {
                    refreshBranches()
                    refreshStatus()
                }
                "cherry-pick", "revert", "merge", "amend", "resolve-conflict" -> {
                    refreshStatus()
                    refreshLog()
                }
                "create-tag", "delete-tag" -> refreshTags()
                "push" -> { /* no refresh needed */ }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSearchResult(msg: Map<String, Any?>) {
        val commits = (msg["commits"] as? List<Map<String, Any?>>)?.map { parseCommit(it) } ?: emptyList()
        _state.update { it.copy(searchResults = commits, isSearching = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleFileHistoryResult(msg: Map<String, Any?>) {
        val commits = (msg["commits"] as? List<Map<String, Any?>>)?.map { parseCommit(it) } ?: emptyList()
        _state.update { it.copy(fileHistory = commits, isLoadingFileHistory = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleBlameResult(msg: Map<String, Any?>) {
        val lines = (msg["lines"] as? List<Map<String, Any?>>)?.map { l ->
            GitBlameLine(
                lineNumber = (l["lineNumber"] as? Number)?.toInt() ?: 0,
                hash = l["hash"] as? String ?: "",
                author = l["author"] as? String ?: "",
                date = l["date"] as? String ?: "",
                content = l["content"] as? String ?: "",
            )
        } ?: emptyList()
        _state.update { it.copy(blameLines = lines, isLoadingBlame = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleCompareResult(msg: Map<String, Any?>) {
        val commits = (msg["commits"] as? List<Map<String, Any?>>)?.map { parseCommit(it) } ?: emptyList()
        val result = GitCompareResult(
            baseBranch = msg["baseBranch"] as? String ?: "",
            compareBranch = msg["compareBranch"] as? String ?: "",
            ahead = (msg["ahead"] as? Number)?.toInt() ?: 0,
            behind = (msg["behind"] as? Number)?.toInt() ?: 0,
            commits = commits,
        )
        _state.update { it.copy(compareResult = result, isLoadingCompare = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleRepoInfoResult(msg: Map<String, Any?>) {
        val remotes = (msg["remotes"] as? List<Map<String, Any?>>)?.map { r ->
            GitRemoteInfo(
                name = r["name"] as? String ?: "",
                url = r["url"] as? String ?: "",
                remoteType = r["remoteType"] as? String ?: "",
            )
        } ?: emptyList()

        val lastCommitMap = msg["lastCommit"] as? Map<String, Any?>
        val lastCommit = lastCommitMap?.let { parseCommit(it) }

        val repoInfo = GitRepoInfo(
            remotes = remotes,
            totalCommits = (msg["totalCommits"] as? Number)?.toLong() ?: 0L,
            currentBranch = msg["currentBranch"] as? String ?: "",
            branchCount = (msg["branchCount"] as? Number)?.toInt() ?: 0,
            tagCount = (msg["tagCount"] as? Number)?.toInt() ?: 0,
            repoSize = msg["repoSize"] as? String ?: "",
            lastCommit = lastCommit,
        )
        _state.update { it.copy(repoInfo = repoInfo, isLoadingRepoInfo = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleTagsResult(msg: Map<String, Any?>) {
        val tags = (msg["tags"] as? List<Map<String, Any?>>)?.map { t ->
            GitTagInfo(
                name = t["name"] as? String ?: "",
                hash = t["hash"] as? String ?: "",
                message = t["message"] as? String,
                date = t["date"] as? String,
                isAnnotated = t["isAnnotated"] as? Boolean ?: false,
            )
        } ?: emptyList()
        _state.update { it.copy(tags = tags, isLoadingTags = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleStashListResult(msg: Map<String, Any?>) {
        val entries = (msg["entries"] as? List<Map<String, Any?>>)?.map { e ->
            GitStashEntry(
                index = (e["index"] as? Number)?.toInt() ?: 0,
                message = e["message"] as? String ?: "",
                date = e["date"] as? String ?: "",
            )
        } ?: emptyList()
        _state.update { it.copy(stashEntries = entries, isLoadingStash = false) }
    }

    // -------------------------------------------------------------------------
    // Graph layout computation
    // -------------------------------------------------------------------------

    private val branchColors = listOf(
        0xFF6366F1, // indigo (primary)
        0xFF22C55E, // green
        0xFFF59E0B, // amber
        0xFFEF4444, // red
        0xFF8B5CF6, // purple
        0xFF06B6D4, // cyan
        0xFFEC4899, // pink
        0xFFF97316, // orange
    )

    /**
     * Compute a full multi-lane graph layout with active lanes and connections.
     *
     * `activeLanes` is a list where index = column, value = commit hash
     * expected in that lane (or null if the lane is free). As we process
     * each commit top-to-bottom:
     *   1. Find which lane this commit occupies (reserved by a child, or allocate new).
     *   2. Record all currently active lanes for vertical-line drawing.
     *   3. Route each parent: first parent inherits the lane; extra parents
     *      either reuse an existing reservation or get a new lane.
     *   4. Record connections (fromCol → toCol) for diagonal-line drawing.
     */
    private fun computeGraphLayout(commits: List<GitCommitInfo>): List<GitGraphNode> {
        if (commits.isEmpty()) return emptyList()

        // lane index → hash that will arrive in that lane (null = free)
        val activeLanes = mutableListOf<String?>()
        val hashToColor = mutableMapOf<String, Long>()

        fun allocateLane(hash: String): Int {
            val free = activeLanes.indexOf(null)
            return if (free >= 0) {
                activeLanes[free] = hash
                free
            } else {
                activeLanes.add(hash)
                activeLanes.size - 1
            }
        }

        fun colorForColumn(col: Int): Long = branchColors[col % branchColors.size]

        return commits.map { commit ->
            // 1. Find this commit's column
            val col = activeLanes.indexOf(commit.hash).let { idx ->
                if (idx >= 0) idx else allocateLane(commit.hash)
            }
            val color = hashToColor[commit.hash] ?: colorForColumn(col)
            hashToColor[commit.hash] = color

            // 2. Snapshot active lanes BEFORE modifying (for drawing vertical lines)
            val lanesSnapshot = activeLanes.mapIndexedNotNull { i, h ->
                if (h != null) LaneInfo(i, hashToColor[h] ?: colorForColumn(i)) else null
            }

            // 3. Free this commit's lane
            activeLanes[col] = null

            // 4. Route parents and build connections
            val connections = mutableListOf<LaneConnection>()

            commit.parents.forEachIndexed { idx, parentHash ->
                // Check if parent already has a lane reserved (by another child)
                val existingLane = activeLanes.indexOf(parentHash)

                if (existingLane >= 0) {
                    // Parent already reserved → draw connection to its lane
                    connections.add(LaneConnection(col, existingLane, hashToColor[parentHash] ?: colorForColumn(existingLane)))
                } else {
                    if (idx == 0) {
                        // First parent inherits this commit's lane
                        activeLanes[col] = parentHash
                        hashToColor[parentHash] = color
                        connections.add(LaneConnection(col, col, color))
                    } else {
                        // Merge parent → new lane
                        val parentCol = allocateLane(parentHash)
                        val parentColor = colorForColumn(parentCol)
                        hashToColor[parentHash] = parentColor
                        connections.add(LaneConnection(col, parentCol, parentColor))
                    }
                }
            }

            GitGraphNode(
                hash = commit.hash,
                shortHash = commit.shortHash,
                message = commit.message,
                author = commit.author,
                date = commit.date,
                parents = commit.parents,
                refs = commit.refs,
                column = col,
                color = color,
                lanes = lanesSnapshot,
                connections = connections,
            )
        }
    }
}
