package com.agentshell.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.GitBranchInfo
import com.agentshell.data.model.GitCommitInfo
import com.agentshell.data.model.GitDiffData
import com.agentshell.data.model.GitFileChange
import com.agentshell.data.model.GitGraphNode
import com.agentshell.data.model.GitOperationResult
import com.agentshell.data.model.GitStatusData
import com.agentshell.data.repository.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    // Operations
    val operationResult: GitOperationResult? = null,
    val error: String? = null,
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

    // -- Stash --

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
        _state.update { it.copy(showCommitDetailSheet = true, selectedCommit = commit) }
    }

    fun hideCommitDetail() {
        _state.update { it.copy(showCommitDetailSheet = false, selectedCommit = null) }
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
                    "git-operation-result" -> handleOperationResult(msg)
                    "error" -> {
                        val message = msg["message"] as? String ?: "Unknown error"
                        _state.update { it.copy(error = message, isLoadingStatus = false, isLoadingDiff = false, isLoadingLog = false, isLoadingBranches = false) }
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
                "push" -> { /* no refresh needed */ }
            }
        }
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
     * Compute column positions for a topological commit graph.
     * Each active branch gets its own column. When a commit merges,
     * its second parent's column is freed.
     */
    private fun computeGraphLayout(commits: List<GitCommitInfo>): List<GitGraphNode> {
        if (commits.isEmpty()) return emptyList()

        // Map from commit hash to the column it occupies
        val activeColumns = mutableListOf<String?>() // column index -> commit hash occupying it (or null = free)
        val hashToColumn = mutableMapOf<String, Int>()
        val hashToColor = mutableMapOf<String, Long>()

        fun allocateColumn(hash: String): Int {
            // Reuse the first free column
            val freeIdx = activeColumns.indexOf(null)
            return if (freeIdx >= 0) {
                activeColumns[freeIdx] = hash
                freeIdx
            } else {
                activeColumns.add(hash)
                activeColumns.size - 1
            }
        }

        return commits.map { commit ->
            // If this commit already has a column assigned (from a child), use it
            val col = hashToColumn[commit.hash] ?: allocateColumn(commit.hash)
            hashToColumn[commit.hash] = col

            // Assign color based on column
            val color = hashToColor[commit.hash] ?: branchColors[col % branchColors.size]
            hashToColor[commit.hash] = color

            // Free this column (we've processed this commit)
            if (col < activeColumns.size) {
                activeColumns[col] = null
            }

            // Assign columns to parents
            commit.parents.forEachIndexed { idx, parentHash ->
                if (parentHash !in hashToColumn) {
                    if (idx == 0) {
                        // First parent inherits current column
                        activeColumns[col] = parentHash
                        hashToColumn[parentHash] = col
                        hashToColor[parentHash] = color
                    } else {
                        // Merge parent gets a new column
                        val parentCol = allocateColumn(parentHash)
                        hashToColumn[parentHash] = parentCol
                        hashToColor[parentHash] = branchColors[parentCol % branchColors.size]
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
            )
        }
    }
}
