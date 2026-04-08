package com.agentshell.data.repository

import com.agentshell.data.remote.WebSocketService
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileBrowserRepository @Inject constructor(
    private val wsService: WebSocketService,
) {
    companion object {
        private val FILE_TYPES = setOf(
            "files-list",
            "files-deleted",
            "file-renamed",
            "file-operation-error",
            "binary-file-content",
            "files-copied",
            "files-moved",
            "file-written",
        )
    }

    fun filteredFileEvents() = wsService.messages.filter { msg ->
        FILE_TYPES.contains(msg["type"] as? String)
    }

    fun listDirectory(path: String) {
        wsService.send(mapOf("type" to "list-files", "path" to path))
    }

    fun deleteFile(path: String, recursive: Boolean = false) {
        wsService.send(mapOf("type" to "delete-files", "paths" to listOf(path), "recursive" to recursive))
    }

    fun renameFile(path: String, newName: String) {
        wsService.send(mapOf("type" to "rename-file", "path" to path, "newName" to newName))
    }

    fun readBinaryFile(path: String) {
        wsService.send(mapOf("type" to "read-binary-file", "path" to path))
    }

    fun writeFile(path: String, contentBase64: String) {
        wsService.send(mapOf("type" to "write-file", "path" to path, "contentBase64" to contentBase64))
    }

    fun copyFiles(sourcePaths: List<String>, destinationPath: String) {
        wsService.send(mapOf("type" to "copy-files", "sourcePaths" to sourcePaths, "destinationPath" to destinationPath))
    }

    fun moveFiles(sourcePaths: List<String>, destinationPath: String) {
        wsService.send(mapOf("type" to "move-files", "sourcePaths" to sourcePaths, "destinationPath" to destinationPath))
    }
}
