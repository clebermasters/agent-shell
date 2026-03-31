package com.agentshell.feature.file_browser

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.FileEntry
import com.agentshell.data.repository.FileBrowserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, MODIFIED_DESC
}

enum class ClipboardMode { NONE, COPY, CUT }

data class FileViewerState(
    val isLoading: Boolean = false,
    val entry: FileEntry? = null,
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showHidden: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val clipboardPaths: List<String> = emptyList(),
    val clipboardMode: ClipboardMode = ClipboardMode.NONE,
    val viewer: FileViewerState? = null,
) {
    val sortedEntries: List<FileEntry>
        get() {
            val visible = if (showHidden) entries else entries.filter { !it.name.startsWith(".") }
            val dirs = visible.filter { it.isDirectory }
            val files = visible.filter { !it.isDirectory }
            fun sortList(list: List<FileEntry>): List<FileEntry> = when (sortMode) {
                SortMode.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                SortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                SortMode.SIZE_ASC -> list.sortedBy { it.size }
                SortMode.SIZE_DESC -> list.sortedByDescending { it.size }
                SortMode.MODIFIED_DESC -> list.sortedByDescending { it.modified ?: "" }
            }
            return sortList(dirs) + sortList(files)
        }
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val repository: FileBrowserRepository,
    private val prefs: PreferencesDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    init {
        observeEvents()
        viewModelScope.launch {
            val raw = prefs.fileSortMode.first()
            val mode = SortMode.entries.find { it.name == raw } ?: SortMode.NAME_ASC
            _state.update { it.copy(sortMode = mode) }
        }
    }

    fun listDirectory(path: String) {
        _state.update { it.copy(isLoading = true, error = null, currentPath = path, selectedPaths = emptySet(), isSelectionMode = false) }
        repository.listDirectory(path)
    }

    fun navigateTo(path: String) {
        listDirectory(path)
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        val parts = current.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val parent = if (parts.size == 1) "/" else "/${parts.dropLast(1).joinToString("/")}"
        listDirectory(parent)
    }

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        viewModelScope.launch { prefs.setFileSortMode(mode.name) }
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
    }

    fun toggleSelection(path: String) {
        _state.update { s ->
            val updated = if (s.selectedPaths.contains(path)) s.selectedPaths - path else s.selectedPaths + path
            s.copy(selectedPaths = updated, isSelectionMode = updated.isNotEmpty())
        }
    }

    fun selectAll() {
        _state.update { s ->
            val allPaths = s.sortedEntries.map { it.path }.toSet()
            s.copy(selectedPaths = allPaths, isSelectionMode = allPaths.isNotEmpty())
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedPaths = emptySet(), isSelectionMode = false) }
    }

    fun deleteSelected() {
        val paths = _state.value.selectedPaths.toList()
        val entries = _state.value.entries
        paths.forEach { path ->
            val entry = entries.firstOrNull { it.path == path }
            repository.deleteFile(path, recursive = entry?.isDirectory == true)
        }
        clearSelection()
    }

    fun deleteSingle(path: String, recursive: Boolean = false) {
        repository.deleteFile(path, recursive)
    }

    fun renameFile(path: String, newName: String) {
        repository.renameFile(path, newName)
    }

    fun copySelected() {
        _state.update { it.copy(clipboardPaths = it.selectedPaths.toList(), clipboardMode = ClipboardMode.COPY, selectedPaths = emptySet(), isSelectionMode = false) }
    }

    fun cutSelected() {
        _state.update { it.copy(clipboardPaths = it.selectedPaths.toList(), clipboardMode = ClipboardMode.CUT, selectedPaths = emptySet(), isSelectionMode = false) }
    }

    fun copySingle(path: String) {
        _state.update { it.copy(clipboardPaths = listOf(path), clipboardMode = ClipboardMode.COPY) }
    }

    fun cutSingle(path: String) {
        _state.update { it.copy(clipboardPaths = listOf(path), clipboardMode = ClipboardMode.CUT) }
    }

    fun pasteFiles() {
        val s = _state.value
        if (s.clipboardPaths.isEmpty() || s.clipboardMode == ClipboardMode.NONE) return
        when (s.clipboardMode) {
            ClipboardMode.COPY -> repository.copyFiles(s.clipboardPaths, s.currentPath)
            ClipboardMode.CUT -> repository.moveFiles(s.clipboardPaths, s.currentPath)
            else -> {}
        }
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboardPaths = emptyList(), clipboardMode = ClipboardMode.NONE) }
    }

    fun openFile(entry: FileEntry) {
        _state.update { it.copy(viewer = FileViewerState(isLoading = true, entry = entry)) }
        repository.readBinaryFile(entry.path)
    }

    fun closeViewer() {
        _state.update { it.copy(viewer = null) }
    }

    fun saveFile(path: String, content: String) {
        _state.update { it.copy(viewer = it.viewer?.copy(isSaving = true, saveError = null)) }
        val encoded = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        repository.writeFile(path, encoded)
    }

    private fun observeEvents() {
        viewModelScope.launch {
            repository.filteredFileEvents().collect { msg ->
                when (msg["type"] as? String) {
                    "files-list" -> handleFileList(msg)
                    "file-deleted" -> handleFileDeleted(msg)
                    "file-renamed" -> handleFileRenamed(msg)
                    "file-operation-error" -> handleError(msg)
                    "binary-file-content" -> handleBinaryFileContent(msg)
                    "files-copied" -> handleFilesCopied(msg)
                    "files-moved" -> handleFilesMoved(msg)
                    "file-written" -> handleFileWritten(msg)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleFileList(msg: Map<String, Any?>) {
        val rawEntries = msg["entries"] as? List<*> ?: return
        val entries = rawEntries.mapNotNull { parseEntry(it as? Map<String, Any?>) }
        _state.update { it.copy(entries = entries, isLoading = false) }
    }

    private fun handleFileDeleted(msg: Map<String, Any?>) {
        val path = msg["path"] as? String ?: return
        _state.update { s -> s.copy(entries = s.entries.filter { it.path != path }) }
        // Refresh directory after delete
        repository.listDirectory(_state.value.currentPath)
    }

    private fun handleFileRenamed(msg: Map<String, Any?>) {
        // Refresh directory after rename
        repository.listDirectory(_state.value.currentPath)
    }

    private fun handleBinaryFileContent(msg: Map<String, Any?>) {
        val error = msg["error"] as? String
        if (error != null) {
            _state.update { it.copy(viewer = it.viewer?.copy(isLoading = false, error = error)) }
            return
        }
        val b64 = msg["contentBase64"] as? String ?: ""
        val mimeType = msg["mimeType"] as? String
        val bytes = if (b64.isNotEmpty()) Base64.decode(b64, Base64.DEFAULT) else null
        _state.update {
            it.copy(viewer = it.viewer?.copy(isLoading = false, bytes = bytes, mimeType = mimeType))
        }
    }

    private fun handleFilesCopied(msg: Map<String, Any?>) {
        val success = msg["success"] as? Boolean ?: false
        if (!success) {
            val error = msg["error"] as? String ?: "Copy failed"
            _state.update { it.copy(error = error) }
        }
        repository.listDirectory(_state.value.currentPath)
    }

    private fun handleFilesMoved(msg: Map<String, Any?>) {
        val success = msg["success"] as? Boolean ?: false
        if (success) {
            _state.update { it.copy(clipboardPaths = emptyList(), clipboardMode = ClipboardMode.NONE) }
        } else {
            val error = msg["error"] as? String ?: "Move failed"
            _state.update { it.copy(error = error) }
        }
        repository.listDirectory(_state.value.currentPath)
    }

    private fun handleError(msg: Map<String, Any?>) {
        val error = msg["error"] as? String ?: "File operation failed"
        _state.update { it.copy(error = error, isLoading = false) }
    }

    private fun handleFileWritten(msg: Map<String, Any?>) {
        val success = msg["success"] as? Boolean ?: false
        val error = msg["error"] as? String
        if (success) {
            // Update the cached bytes in viewer with the freshly-saved content
            _state.update { it.copy(viewer = it.viewer?.copy(isSaving = false, saveError = null)) }
        } else {
            _state.update { it.copy(viewer = it.viewer?.copy(isSaving = false, saveError = error ?: "Save failed")) }
        }
    }

    private fun parseEntry(map: Map<String, Any?>?): FileEntry? {
        val m = map ?: return null
        val name = m["name"] as? String ?: return null
        val path = m["path"] as? String ?: return null
        return FileEntry(
            name = name,
            path = path,
            isDirectory = m["isDirectory"] as? Boolean ?: false,
            size = (m["size"] as? Number)?.toLong() ?: 0L,
            modified = m["modified"] as? String,
        )
    }
}
