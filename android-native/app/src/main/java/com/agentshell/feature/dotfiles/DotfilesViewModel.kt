package com.agentshell.feature.dotfiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.DotFile
import com.agentshell.data.model.DotFileTemplate
import com.agentshell.data.model.DotFileType
import com.agentshell.data.model.DotFileVersion
import com.agentshell.data.repository.DotfilesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DotfilesUiState(
    val files: List<DotFile> = emptyList(),
    val filesByType: Map<DotFileType, List<DotFile>> = emptyMap(),
    val selectedFile: DotFile? = null,
    val fileContent: String = "",
    val versions: List<DotFileVersion> = emptyList(),
    val templates: List<DotFileTemplate> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

@HiltViewModel
class DotfilesViewModel @Inject constructor(
    private val repository: DotfilesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DotfilesUiState())
    val state: StateFlow<DotfilesUiState> = _state.asStateFlow()

    // Expose repository shared state for the editor screen
    val repoSelectedFile = repository.selectedFile
    val repoFileContent = repository.fileContent
    val repoIsLoading = repository.isLoadingContent

    init {
        observeEvents()
        requestDotfiles()
    }

    fun requestDotfiles() {
        _state.update { it.copy(isLoading = true, error = null) }
        repository.requestDotfiles()
    }

    fun selectFile(file: DotFile) {
        _state.update { it.copy(selectedFile = file, fileContent = "", versions = emptyList()) }
        repository.selectAndLoadFile(file)
    }

    fun saveFile(path: String, content: String) {
        _state.update { it.copy(isSaving = true) }
        repository.saveFile(path, content)
    }

    fun getHistory(path: String) {
        repository.getHistory(path)
    }

    fun restoreVersion(path: String, versionId: String) {
        repository.restoreVersion(path, versionId)
    }

    fun getTemplates() {
        repository.getTemplates()
    }

    fun applyTemplate(path: String, templateName: String) {
        repository.applyTemplate(path, templateName)
    }

    fun clearSaveSuccess() {
        _state.update { it.copy(saveSuccess = false) }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            repository.filteredDotfileEvents().collect { msg ->
                when (msg["type"] as? String) {
                    "dotfiles-list", "dotfiles_list" -> handleFilesList(msg)
                    "dotfile-content", "dotfile_content" -> handleFileContent(msg)
                    "dotfile-written", "dotfile_written" -> handleFileSaved(msg)
                    "dotfile-history", "dotfile_history" -> handleFileHistory(msg)
                    "dotfile-restored", "dotfile_restored" -> handleVersionRestored(msg)
                    "dotfile-templates", "dotfile_templates" -> handleTemplates(msg)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleFilesList(msg: Map<String, Any?>) {
        val rawFiles = msg["files"] as? List<*> ?: return
        val files = rawFiles.mapNotNull { parseFile(it as? Map<String, Any?>) }
        val grouped = DotFileType.values().associateWith { type ->
            files.filter { it.fileTypeEnum == type }
        }.filterValues { it.isNotEmpty() }
        _state.update { it.copy(files = files, filesByType = grouped, isLoading = false) }
    }

    private fun handleFileContent(msg: Map<String, Any?>) {
        val content = msg["content"] as? String ?: ""
        val error = msg["error"] as? String
        _state.update { it.copy(fileContent = content, isLoading = false, error = error) }
        // Also push to the shared repository state (for the editor screen)
        repository.onFileContentReceived(content, error)
    }

    private fun handleFileSaved(msg: Map<String, Any?>) {
        val error = msg["error"] as? String
        _state.update { it.copy(isSaving = false, saveSuccess = error == null, error = error) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleFileHistory(msg: Map<String, Any?>) {
        val rawVersions = msg["versions"] as? List<*> ?: return
        val versions = rawVersions.mapNotNull { parseVersion(it as? Map<String, Any?>) }
        _state.update { it.copy(versions = versions) }
    }

    private fun handleVersionRestored(msg: Map<String, Any?>) {
        val content = msg["content"] as? String ?: return
        _state.update { it.copy(fileContent = content) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleTemplates(msg: Map<String, Any?>) {
        val rawTemplates = msg["templates"] as? List<*> ?: return
        val templates = rawTemplates.mapNotNull { parseTemplate(it as? Map<String, Any?>) }
        _state.update { it.copy(templates = templates) }
    }

    private fun parseFile(map: Map<String, Any?>?): DotFile? {
        val m = map ?: return null
        val path = m["path"] as? String ?: return null
        val name = m["name"] as? String ?: path.substringAfterLast('/')
        return DotFile(
            path = path,
            name = name,
            isDirectory = m["isDirectory"] as? Boolean ?: false,
            size = (m["size"] as? Number)?.toLong() ?: 0L,
            modified = m["modified"] as? String,
            content = m["content"] as? String,
            exists = m["exists"] as? Boolean ?: true,
            writable = m["writable"] as? Boolean ?: true,
            fileType = m["fileType"] as? String ?: DotFileType.OTHER.name,
        )
    }

    private fun parseVersion(map: Map<String, Any?>?): DotFileVersion? {
        val m = map ?: return null
        val id = m["id"] as? String ?: return null
        val timestamp = m["timestamp"] as? String ?: return null
        return DotFileVersion(
            id = id,
            timestamp = timestamp,
            commitMessage = m["commitMessage"] as? String,
            size = (m["size"] as? Number)?.toLong() ?: 0L,
            content = m["content"] as? String,
        )
    }

    private fun parseTemplate(map: Map<String, Any?>?): DotFileTemplate? {
        val m = map ?: return null
        val name = m["name"] as? String ?: return null
        val fileType = m["fileType"] as? String ?: DotFileType.OTHER.name
        val description = m["description"] as? String ?: ""
        val content = m["content"] as? String ?: ""
        return DotFileTemplate(name = name, fileType = fileType, description = description, content = content)
    }
}
