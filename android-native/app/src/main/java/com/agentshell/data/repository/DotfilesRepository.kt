package com.agentshell.data.repository

import android.util.Log
import com.agentshell.data.model.DotFile
import com.agentshell.data.remote.WebSocketService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton repository for dotfile operations.
 *
 * Holds shared state for the currently selected file and its content,
 * so both DotfilesScreen (list) and DotfileEditorScreen (editor) can
 * access the same data without passing paths through navigation args.
 */
@Singleton
class DotfilesRepository @Inject constructor(
    private val wsService: WebSocketService,
) {
    companion object {
        private val DOTFILE_TYPES = setOf(
            "dotfiles-list", "dotfiles_list",
            "dotfile-content", "dotfile_content",
            "dotfile-written", "dotfile_written",
            "dotfile-history", "dotfile_history",
            "dotfile-restored", "dotfile_restored",
            "dotfile-templates", "dotfile_templates",
        )
    }

    // ── Shared state (survives navigation between list and editor) ─────────

    private val _selectedFile = MutableStateFlow<DotFile?>(null)
    val selectedFile: StateFlow<DotFile?> = _selectedFile.asStateFlow()

    private val _fileContent = MutableStateFlow<String?>(null)
    val fileContent: StateFlow<String?> = _fileContent.asStateFlow()

    private val _isLoadingContent = MutableStateFlow(false)
    val isLoadingContent: StateFlow<Boolean> = _isLoadingContent.asStateFlow()

    /** Select a file and request its content from the backend. */
    fun selectAndLoadFile(file: DotFile) {
        _selectedFile.value = file
        _fileContent.value = null
        if (!file.exists) {
            // File doesn't exist on server — open empty editor to create it
            _fileContent.value = ""
            _isLoadingContent.value = false
        } else {
            _isLoadingContent.value = true
            readFile(file.path)
        }
    }

    /** Called when dotfile-content message arrives. */
    fun onFileContentReceived(content: String?, error: String? = null) {
        Log.d("DotfilesRepo", "onFileContentReceived: content=${content?.length ?: -1} chars, error=$error")
        // If error (file not readable / doesn't exist), show empty editor for creating the file
        _fileContent.value = content ?: ""
        _isLoadingContent.value = false
    }

    /** Called when the selected file doesn't exist — open empty editor to create it. */
    fun openEmptyEditor(file: DotFile) {
        _selectedFile.value = file
        _fileContent.value = ""
        _isLoadingContent.value = false
    }

    // ── WebSocket commands ─────────────────────────────────────────────────

    fun filteredDotfileEvents() = wsService.messages.filter { msg ->
        DOTFILE_TYPES.contains(msg["type"] as? String)
    }

    fun requestDotfiles() {
        wsService.send(mapOf("type" to "list-dotfiles"))
    }

    fun readFile(path: String) {
        // Backend's validate_and_resolve_path re-roots absolute paths under $HOME.
        // So "/home/user/.bashrc" becomes "$HOME/home/user/.bashrc" (wrong).
        // Convert absolute paths to ~/relative format which the backend handles correctly.
        val safePath = if (path.startsWith("/home/")) {
            // "/home/user/.bashrc" → "~/.bashrc" (strip /home/username/)
            val afterHome = path.removePrefix("/home/")
            val slashIdx = afterHome.indexOf('/')
            if (slashIdx >= 0) "~" + afterHome.substring(slashIdx) else path
        } else {
            path
        }
        Log.d("DotfilesRepo", "readFile: path='$path' → safePath='$safePath'")
        wsService.send(mapOf("type" to "read-dotfile", "path" to safePath))
    }

    fun saveFile(path: String, content: String) {
        wsService.send(mapOf("type" to "write-dotfile", "path" to path, "content" to content))
    }

    fun getHistory(path: String) {
        wsService.send(mapOf("type" to "get-dotfile-history", "path" to path))
    }

    fun restoreVersion(path: String, versionId: String) {
        wsService.send(mapOf("type" to "restore-dotfile-version", "path" to path, "timestamp" to versionId))
    }

    fun getTemplates() {
        wsService.send(mapOf("type" to "get-dotfile-templates"))
    }

    fun applyTemplate(path: String, templateContent: String) {
        wsService.send(mapOf("type" to "write-dotfile", "path" to path, "content" to templateContent))
    }
}
