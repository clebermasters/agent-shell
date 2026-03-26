package com.agentshell.feature.file_browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.FileEntry
import com.agentshell.data.repository.FileBrowserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, MODIFIED_DESC
}

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showHidden: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
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
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    init {
        observeEvents()
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

    private fun observeEvents() {
        viewModelScope.launch {
            repository.filteredFileEvents().collect { msg ->
                when (msg["type"] as? String) {
                    "file-list" -> handleFileList(msg)
                    "file-deleted" -> handleFileDeleted(msg)
                    "file-renamed" -> handleFileRenamed(msg)
                    "file-operation-error" -> handleError(msg)
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

    private fun handleError(msg: Map<String, Any?>) {
        val error = msg["error"] as? String ?: "File operation failed"
        _state.update { it.copy(error = error, isLoading = false) }
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
