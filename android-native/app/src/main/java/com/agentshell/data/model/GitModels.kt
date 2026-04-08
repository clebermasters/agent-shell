package com.agentshell.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Git Status
// ---------------------------------------------------------------------------

data class GitStatusData(
    val branch: String = "",
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: List<GitFileChange> = emptyList(),
    val modified: List<GitFileChange> = emptyList(),
    val untracked: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
) {
    val totalChanges: Int
        get() = staged.size + modified.size + untracked.size + conflicts.size

    val hasChanges: Boolean
        get() = totalChanges > 0
}

data class GitFileChange(
    val path: String,
    val status: String,   // M, A, D, R, C
    val additions: Int? = null,
    val deletions: Int? = null,
) {
    val filename: String
        get() = path.substringAfterLast('/')

    val directory: String
        get() = path.substringBeforeLast('/', "")

    val statusLabel: String
        get() = when (status) {
            "M" -> "Modified"
            "A" -> "Added"
            "D" -> "Deleted"
            "R" -> "Renamed"
            "C" -> "Copied"
            else -> status
        }
}

// ---------------------------------------------------------------------------
// Git Diff
// ---------------------------------------------------------------------------

data class GitDiffData(
    val filePath: String = "",
    val diff: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
)

// ---------------------------------------------------------------------------
// Git Commit / Log
// ---------------------------------------------------------------------------

data class GitCommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val date: String,
    val parents: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
) {
    val isMerge: Boolean get() = parents.size > 1

    val refTags: List<String>
        get() = refs.filter { it.startsWith("tag: ") }.map { it.removePrefix("tag: ") }

    val refBranches: List<String>
        get() = refs.filter { !it.startsWith("tag: ") }
}

// ---------------------------------------------------------------------------
// Git Branch
// ---------------------------------------------------------------------------

data class GitBranchInfo(
    val name: String,
    val current: Boolean = false,
    val tracking: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val lastCommit: String? = null,
)

// ---------------------------------------------------------------------------
// Git Graph Node (commit + layout info)
// ---------------------------------------------------------------------------

data class GitGraphNode(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val date: String,
    val parents: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val column: Int = 0,
    val color: Long = 0,
    // Active lanes passing through this row (vertical lines)
    val lanes: List<LaneInfo> = emptyList(),
    // Connections from this row to the next (diagonal/merge lines)
    val connections: List<LaneConnection> = emptyList(),
) {
    val isMerge: Boolean get() = parents.size > 1
}

data class LaneInfo(
    val column: Int,
    val color: Long,
)

data class LaneConnection(
    val fromColumn: Int,
    val toColumn: Int,
    val color: Long,
)

// ---------------------------------------------------------------------------
// Git Operation Result
// ---------------------------------------------------------------------------

data class GitOperationResult(
    val operation: String,
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Blame
// ---------------------------------------------------------------------------

data class GitBlameLine(
    val lineNumber: Int,
    val hash: String,
    val author: String,
    val date: String,
    val content: String,
)

// ---------------------------------------------------------------------------
// Compare
// ---------------------------------------------------------------------------

data class GitCompareResult(
    val baseBranch: String,
    val compareBranch: String,
    val ahead: Int,
    val behind: Int,
    val commits: List<GitCommitInfo> = emptyList(),
)

// ---------------------------------------------------------------------------
// Repo Info
// ---------------------------------------------------------------------------

data class GitRepoInfo(
    val remotes: List<GitRemoteInfo> = emptyList(),
    val totalCommits: Long = 0,
    val currentBranch: String = "",
    val branchCount: Int = 0,
    val tagCount: Int = 0,
    val repoSize: String = "",
    val lastCommit: GitCommitInfo? = null,
)

data class GitRemoteInfo(
    val name: String,
    val url: String,
    val remoteType: String = "",
)

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

data class GitTagInfo(
    val name: String,
    val hash: String,
    val message: String? = null,
    val date: String? = null,
    val isAnnotated: Boolean = false,
)

// ---------------------------------------------------------------------------
// Stash
// ---------------------------------------------------------------------------

data class GitStashEntry(
    val index: Int,
    val message: String,
    val date: String,
)
