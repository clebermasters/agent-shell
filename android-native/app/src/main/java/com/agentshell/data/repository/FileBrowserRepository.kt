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
            "file-deleted",
            "file-renamed",
            "file-operation-error",
            "binary-file-content",
        )
    }

    fun filteredFileEvents() = wsService.messages.filter { msg ->
        FILE_TYPES.contains(msg["type"] as? String)
    }

    fun listDirectory(path: String) {
        wsService.send(mapOf("type" to "list-files", "path" to path))
    }

    fun deleteFile(path: String, recursive: Boolean = false) {
        wsService.send(mapOf("type" to "delete-file", "path" to path, "recursive" to recursive))
    }

    fun renameFile(path: String, newName: String) {
        wsService.send(mapOf("type" to "rename-file", "path" to path, "newName" to newName))
    }

    fun readBinaryFile(path: String) {
        wsService.send(mapOf("type" to "read-binary-file", "path" to path))
    }
}
