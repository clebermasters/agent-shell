package com.agentshell.data.remote

// ---------------------------------------------------------------------------
// WebSocketCommands.kt
//
// Extension functions on WebSocketService covering every message type used by
// the Flutter client.  Each function builds the correct payload map and
// delegates to WebSocketService.send().
//
// Grouped sections:
//   1. Session commands
//   2. Window commands
//   3. Terminal commands
//   4. Cron commands
//   5. Dotfile commands
//   6. Chat commands
//   7. ACP commands
//   8. System commands
//   9. File commands
//  10. Audio commands
// ---------------------------------------------------------------------------

// =============================================================================
// 1. SESSION COMMANDS
// =============================================================================

/** Request the full list of TMUX sessions from the backend. */
fun WebSocketService.requestSessions() {
    send(mapOf("type" to "list-sessions"))
}

/**
 * Create a new TMUX session, optionally with a [name].
 * Omitting [name] lets the backend auto-name the session.
 */
fun WebSocketService.createSession(name: String? = null) {
    send(buildMap {
        put("type", "create-session")
        if (name != null) put("name", name)
    })
}

/** Kill the TMUX session identified by [sessionName]. */
fun WebSocketService.killSession(sessionName: String) {
    send(mapOf(
        "type"        to "kill-session",
        "sessionName" to sessionName,
    ))
}

/** Rename a TMUX session from [sessionName] to [newName]. */
fun WebSocketService.renameSession(sessionName: String, newName: String) {
    send(mapOf(
        "type"        to "rename-session",
        "sessionName" to sessionName,
        "newName"     to newName,
    ))
}

/**
 * Attach to [sessionName].
 *
 * @param cols           Terminal width in columns (default 80).
 * @param rows           Terminal height in rows (default 24).
 * @param windowIndex    Which window to focus; omit to use current.
 * @param requestHistory Whether to request scrollback history (default true).
 */
fun WebSocketService.attachSession(
    sessionName: String,
    cols: Int = 80,
    rows: Int = 24,
    windowIndex: Int? = null,
    requestHistory: Boolean = true,
) {
    send(buildMap {
        put("type",           "attach-session")
        put("sessionName",    sessionName)
        put("cols",           cols)
        put("rows",           rows)
        put("windowIndex",    windowIndex ?: 0)
        put("requestHistory", requestHistory)
    })
}

/** Ask the backend for the current working directory of [sessionName]. */
fun WebSocketService.getSessionCwd(sessionName: String) {
    send(mapOf(
        "type"        to "get-session-cwd",
        "sessionName" to sessionName,
    ))
}

// =============================================================================
// 2. WINDOW COMMANDS
// =============================================================================

/** Request the list of windows for [sessionName]. */
fun WebSocketService.requestWindows(sessionName: String) {
    send(mapOf(
        "type"        to "list-windows",
        "sessionName" to sessionName,
    ))
}

/** Select (focus) [windowIndex] within [sessionName]. */
fun WebSocketService.selectWindow(sessionName: String, windowIndex: Int) {
    send(mapOf(
        "type"        to "select-window",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
    ))
}

/** Create a new window inside [sessionName], optionally named [name]. */
fun WebSocketService.createWindow(sessionName: String, name: String? = null) {
    send(buildMap {
        put("type",        "create-window")
        put("sessionName", sessionName)
        if (name != null) put("name", name)
    })
}

/** Kill the window at [windowIndex] inside [sessionName]. */
fun WebSocketService.killWindow(sessionName: String, windowIndex: Int) {
    send(mapOf(
        "type"        to "kill-window",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
    ))
}

/** Rename window [windowIndex] inside [sessionName] to [newName]. */
fun WebSocketService.renameWindow(sessionName: String, windowIndex: Int, newName: String) {
    send(mapOf(
        "type"        to "rename-window",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
        "newName"     to newName,
    ))
}

// =============================================================================
// 3. TERMINAL COMMANDS
// =============================================================================

/** Send raw PTY input [data] to the currently attached terminal. */
fun WebSocketService.sendTerminalData(data: String) {
    send(mapOf(
        "type" to "input",
        "data" to data,
    ))
}

/**
 * Send [data] to a terminal via tmux send-keys, targeting an optional
 * [sessionName] and/or [windowIndex].
 */
fun WebSocketService.sendInputViaTmux(
    data: String,
    sessionName: String? = null,
    windowIndex: Int? = null,
) {
    send(buildMap {
        put("type", "inputViaTmux")
        if (sessionName != null) put("sessionName", sessionName)
        if (windowIndex != null) put("windowIndex", windowIndex)
        put("data", data)
    })
}

/**
 * Send a chat message to a tmux session. The backend persists the message to
 * the chat event store, broadcasts it to all clients, and then sends it to
 * the tmux pane via send-keys. Preferred over [sendInputViaTmux] for chat
 * because it properly records the message in chat history.
 */
fun WebSocketService.sendChatMessage(
    sessionName: String,
    windowIndex: Int,
    message: String,
) {
    send(mapOf(
        "type" to "send-chat-message",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
        "message" to message,
        "notify" to false,
    ))
}

/** Send an Enter key-press to the currently attached terminal. */
fun WebSocketService.sendEnterKey() {
    send(mapOf("type" to "sendEnterKey"))
}

/** Notify the backend that the terminal has been resized to [cols]×[rows]. */
fun WebSocketService.resizeTerminal(cols: Int, rows: Int) {
    send(mapOf(
        "type" to "resize",
        "cols" to cols,
        "rows" to rows,
    ))
}

// =============================================================================
// 4. CRON COMMANDS
// =============================================================================

/** Request all cron jobs defined on the backend. */
fun WebSocketService.requestCronJobs() {
    send(mapOf("type" to "list-cron-jobs"))
}

/**
 * Create a new cron job.
 *
 * [job] must contain the fields expected by the backend (e.g. `command`,
 * `schedule`, `enabled`).  Pass a plain [Map] so this layer stays
 * model-independent.
 */
fun WebSocketService.createCronJob(job: Map<String, Any?>) {
    send(mapOf(
        "type" to "create-cron-job",
        "job"  to job,
    ))
}

/**
 * Update an existing cron job identified by [id].
 *
 * [job] contains only the fields to update.
 */
fun WebSocketService.updateCronJob(id: String, job: Map<String, Any?>) {
    send(mapOf(
        "type" to "update-cron-job",
        "id"   to id,
        "job"  to job,
    ))
}

/** Delete the cron job with [id]. */
fun WebSocketService.deleteCronJob(id: String) {
    send(mapOf(
        "type" to "delete-cron-job",
        "id"   to id,
    ))
}

/** Enable or disable cron job [id] according to [enabled]. */
fun WebSocketService.toggleCronJob(id: String, enabled: Boolean) {
    send(mapOf(
        "type"    to "toggle-cron-job",
        "id"      to id,
        "enabled" to enabled,
    ))
}

/** Run [command] immediately on the backend to test how it behaves as a cron command. */
fun WebSocketService.testCronCommand(command: String) {
    send(mapOf(
        "type"    to "test-cron-command",
        "command" to command,
    ))
}

// =============================================================================
// 5. DOTFILE COMMANDS
// =============================================================================

/** Request the list of tracked dotfiles. */
fun WebSocketService.requestDotfiles() {
    send(mapOf("type" to "list-dotfiles"))
}

/** Request the text content of the dotfile at [path]. */
fun WebSocketService.requestDotfileContent(path: String) {
    send(mapOf(
        "type" to "read-dotfile",
        "path" to path,
    ))
}

/** Request the binary (base-64) content of the file at [path]. */
fun WebSocketService.requestBinaryFileContent(path: String) {
    send(mapOf(
        "type" to "read-binary-file",
        "path" to path,
    ))
}

/** Save [content] to the dotfile at [path]. */
fun WebSocketService.saveDotfile(path: String, content: String) {
    send(mapOf(
        "type"    to "write-dotfile",
        "path"    to path,
        "content" to content,
    ))
}

/** Request the version history for the dotfile at [path]. */
fun WebSocketService.requestDotfileHistory(path: String) {
    send(mapOf(
        "type" to "get-dotfile-history",
        "path" to path,
    ))
}

/** Restore the version of [path] that was saved at [timestamp]. */
fun WebSocketService.restoreDotfileVersion(path: String, timestamp: String) {
    send(mapOf(
        "type"      to "restore-dotfile-version",
        "path"      to path,
        "timestamp" to timestamp,
    ))
}

/** Request all available dotfile templates from the backend. */
fun WebSocketService.requestDotfileTemplates() {
    send(mapOf("type" to "get-dotfile-templates"))
}

// =============================================================================
// 6. CHAT COMMANDS
// =============================================================================

/**
 * Start watching the chat log for TMUX window [windowIndex] in [sessionName].
 *
 * @param limit Optional maximum number of messages to return initially.
 */
fun WebSocketService.watchChatLog(sessionName: String, windowIndex: Int, limit: Int? = null) {
    send(buildMap {
        put("type",        "watch-chat-log")
        put("sessionName", sessionName)
        put("windowIndex", windowIndex)
        if (limit != null) put("limit", limit)
    })
}

/**
 * Start watching the ACP chat log for [sessionId].
 *
 * @param windowIndex Optional window index context.
 * @param limit       Optional maximum number of messages to return initially.
 */
fun WebSocketService.watchAcpChatLog(sessionId: String, windowIndex: Int? = null, limit: Int? = null) {
    send(buildMap {
        put("type",      "watch-acp-chat-log")
        put("sessionId", sessionId)
        if (windowIndex != null) put("windowIndex", windowIndex)
        if (limit != null) put("limit", limit)
    })
}

/** Stop watching any currently watched chat log. */
fun WebSocketService.unwatchChatLog() {
    send(mapOf("type" to "unwatch-chat-log"))
}

/**
 * Load an additional page of chat history.
 *
 * @param offset Number of messages already loaded (pagination offset).
 * @param limit  Number of messages to fetch.
 */
fun WebSocketService.loadMoreChatHistory(
    sessionName: String,
    windowIndex: Int,
    offset: Int,
    limit: Int,
) {
    send(mapOf(
        "type"        to "load-more-chat-history",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
        "offset"      to offset,
        "limit"       to limit,
    ))
}

/** Clear the chat log for TMUX window [windowIndex] in [sessionName]. */
fun WebSocketService.clearChatLog(sessionName: String, windowIndex: Int) {
    send(mapOf(
        "type"        to "clear-chat-log",
        "sessionName" to sessionName,
        "windowIndex" to windowIndex,
    ))
}

/** Clear the ACP conversation history for [sessionId]. */
fun WebSocketService.acpClearHistory(sessionId: String) {
    send(mapOf(
        "type"      to "acp-clear-history",
        "sessionId" to sessionId,
    ))
}

/**
 * Send a file attachment to a TMUX chat window.
 *
 * @param data   Base-64 encoded file bytes.
 * @param prompt Optional text to accompany the file.
 */
fun WebSocketService.sendFileToChat(
    sessionName: String,
    windowIndex: Int,
    filename: String,
    mimeType: String,
    data: String,
    prompt: String? = null,
) {
    send(buildMap {
        put("type",        "send-file-to-chat")
        put("sessionName", sessionName)
        put("windowIndex", windowIndex)
        put("file", mapOf(
            "filename" to filename,
            "mimeType" to mimeType,
            "data"     to data,
        ))
        put("prompt", prompt)
    })
}

/**
 * Send a file attachment to an ACP chat session.
 *
 * @param data   Base-64 encoded file bytes.
 * @param prompt Optional text to accompany the file.
 * @param cwd    Optional working directory context.
 */
fun WebSocketService.sendFileToAcpChat(
    sessionId: String,
    filename: String,
    mimeType: String,
    data: String,
    prompt: String? = null,
    cwd: String? = null,
) {
    send(buildMap {
        put("type",      "send-file-to-acp-chat")
        put("sessionId", sessionId)
        put("file", mapOf(
            "filename" to filename,
            "mimeType" to mimeType,
            "data"     to data,
        ))
        put("prompt", prompt)
        put("cwd", cwd)
    })
}

// =============================================================================
// 7. ACP COMMANDS
// =============================================================================

/**
 * Switch between backend modes.
 *
 * @param backend Either `"tmux"` or `"acp"`.
 */
fun WebSocketService.selectBackend(backend: String) {
    send(mapOf(
        "type"    to "select-backend",
        "backend" to backend,
    ))
}

/** Create a new ACP session rooted at [cwd]. */
fun WebSocketService.acpCreateSession(cwd: String) {
    send(mapOf(
        "type" to "acp-create-session",
        "cwd"  to cwd,
    ))
}

/** Resume an existing ACP session [sessionId], setting the working directory to [cwd]. */
fun WebSocketService.acpResumeSession(sessionId: String, cwd: String) {
    send(mapOf(
        "type"      to "acp-resume-session",
        "sessionId" to sessionId,
        "cwd"       to cwd,
    ))
}

/** Fork ACP session [sessionId] into a new session rooted at [cwd]. */
fun WebSocketService.acpForkSession(sessionId: String, cwd: String) {
    send(mapOf(
        "type"      to "acp-fork-session",
        "sessionId" to sessionId,
        "cwd"       to cwd,
    ))
}

/** Request the list of all ACP sessions. */
fun WebSocketService.acpListSessions() {
    send(mapOf("type" to "acp-list-sessions"))
}

/** Send a user [message] prompt to ACP session [sessionId]. */
fun WebSocketService.acpSendPrompt(sessionId: String, message: String) {
    send(mapOf(
        "type"      to "acp-send-prompt",
        "sessionId" to sessionId,
        "message"   to message,
    ))
}

/** Cancel the currently running prompt in ACP session [sessionId]. */
fun WebSocketService.acpCancelPrompt(sessionId: String) {
    send(mapOf(
        "type"      to "acp-cancel-prompt",
        "sessionId" to sessionId,
    ))
}

/** Change the active model in ACP session [sessionId] to [modelId]. */
fun WebSocketService.acpSetModel(sessionId: String, modelId: String) {
    send(mapOf(
        "type"      to "acp-set-model",
        "sessionId" to sessionId,
        "modelId"   to modelId,
    ))
}

/** Change the active mode in ACP session [sessionId] to [modeId]. */
fun WebSocketService.acpSetMode(sessionId: String, modeId: String) {
    send(mapOf(
        "type"      to "acp-set-mode",
        "sessionId" to sessionId,
        "modeId"    to modeId,
    ))
}

/**
 * Load paginated conversation history for ACP session [sessionId].
 *
 * @param offset Messages to skip (default 0).
 * @param limit  Messages to return (default 50, matches Flutter default).
 */
fun WebSocketService.acpLoadHistory(sessionId: String, offset: Int? = null, limit: Int? = null) {
    send(buildMap {
        put("type",      "acp-load-history")
        put("sessionId", sessionId)
        put("offset",    offset ?: 0)
        put("limit",     limit ?: 50)
    })
}

/** Delete ACP session [sessionId] and its history. */
fun WebSocketService.deleteAcpSession(sessionId: String) {
    send(mapOf(
        "type"      to "acp-delete-session",
        "sessionId" to sessionId,
    ))
}

/**
 * Respond to a permission request from the ACP agent.
 *
 * @param requestId The permission request identifier sent by the backend.
 * @param optionId  The chosen option (e.g. `"allow"`, `"deny"`).
 */
fun WebSocketService.acpRespondPermission(requestId: String, optionId: String) {
    send(mapOf(
        "type"      to "acp-respond-permission",
        "requestId" to requestId,
        "optionId"  to optionId,
    ))
}

// =============================================================================
// 8. SYSTEM COMMANDS
// =============================================================================

/** Request current system resource statistics (CPU, memory, etc.). */
fun WebSocketService.requestSystemStats() {
    send(mapOf("type" to "get-stats"))
}

/** Request Claude Max subscription usage metrics. */
fun WebSocketService.requestClaudeUsage() {
    send(mapOf("type" to "get-claude-usage"))
}

// =============================================================================
// 9. FILE COMMANDS
// =============================================================================

/** List files and directories under [path] on the backend host. */
fun WebSocketService.listFiles(path: String) {
    send(mapOf(
        "type" to "list-files",
        "path" to path,
    ))
}

/**
 * Delete files at [paths].
 *
 * @param recursive When `true`, delete directories recursively.
 */
fun WebSocketService.deleteFiles(paths: List<String>, recursive: Boolean = false) {
    send(mapOf(
        "type"      to "delete-files",
        "paths"     to paths,
        "recursive" to recursive,
    ))
}

/** Rename (or move) the file at [path] to [newName]. */
fun WebSocketService.renameFile(path: String, newName: String) {
    send(mapOf(
        "type"    to "rename-file",
        "path"    to path,
        "newName" to newName,
    ))
}

// =============================================================================
// 10. GIT COMMANDS
// =============================================================================

/** Request git status for a session's working directory. */
fun WebSocketService.gitStatus(sessionName: String? = null, path: String? = null) {
    send(buildMap {
        put("type", "git-status")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
    })
}

/** Request a diff for a specific file or the whole working tree. */
fun WebSocketService.gitDiff(
    sessionName: String? = null,
    path: String? = null,
    filePath: String? = null,
    staged: Boolean = false,
) {
    send(buildMap {
        put("type", "git-diff")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        if (filePath != null) put("filePath", filePath)
        if (staged) put("staged", true)
    })
}

/** Request git log (commit history). */
fun WebSocketService.gitLog(
    sessionName: String? = null,
    path: String? = null,
    limit: Int = 50,
    offset: Int = 0,
) {
    send(buildMap {
        put("type", "git-log")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("limit", limit)
        put("offset", offset)
    })
}

/** Request list of local branches. */
fun WebSocketService.gitBranches(sessionName: String? = null, path: String? = null) {
    send(buildMap {
        put("type", "git-branches")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
    })
}

/** Switch to a branch. */
fun WebSocketService.gitCheckout(sessionName: String? = null, path: String? = null, branch: String) {
    send(buildMap {
        put("type", "git-checkout")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("branch", branch)
    })
}

/** Create a new branch. */
fun WebSocketService.gitCreateBranch(
    sessionName: String? = null,
    path: String? = null,
    branch: String,
    startPoint: String? = null,
) {
    send(buildMap {
        put("type", "git-create-branch")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("branch", branch)
        if (startPoint != null) put("startPoint", startPoint)
    })
}

/** Delete a branch. */
fun WebSocketService.gitDeleteBranch(sessionName: String? = null, path: String? = null, branch: String) {
    send(buildMap {
        put("type", "git-delete-branch")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("branch", branch)
    })
}

/** Stage files for commit. */
fun WebSocketService.gitStage(sessionName: String? = null, path: String? = null, files: List<String>) {
    send(buildMap {
        put("type", "git-stage")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("files", files)
    })
}

/** Unstage files. */
fun WebSocketService.gitUnstage(sessionName: String? = null, path: String? = null, files: List<String>) {
    send(buildMap {
        put("type", "git-unstage")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("files", files)
    })
}

/** Commit staged changes. */
fun WebSocketService.gitCommit(sessionName: String? = null, path: String? = null, message: String) {
    send(buildMap {
        put("type", "git-commit")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("message", message)
    })
}

/** Push to remote. */
fun WebSocketService.gitPush(
    sessionName: String? = null,
    path: String? = null,
    remote: String? = null,
    branch: String? = null,
) {
    send(buildMap {
        put("type", "git-push")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        if (remote != null) put("remote", remote)
        if (branch != null) put("branch", branch)
    })
}

/** Pull from remote. */
fun WebSocketService.gitPull(sessionName: String? = null, path: String? = null) {
    send(buildMap {
        put("type", "git-pull")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
    })
}

/** Stash operations: push, pop, list. */
fun WebSocketService.gitStash(sessionName: String? = null, path: String? = null, action: String) {
    send(buildMap {
        put("type", "git-stash")
        if (sessionName != null) put("sessionName", sessionName)
        if (path != null) put("path", path)
        put("action", action)
    })
}

// =============================================================================
// 11. AUDIO COMMANDS
// =============================================================================

/**
 * Control the backend audio stream.
 *
 * @param action Either `"start"` or `"stop"`.
 */
fun WebSocketService.audioControl(action: String) {
    send(mapOf(
        "type"   to "audio-control",
        "action" to action,
    ))
}
