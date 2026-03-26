package com.agentshell.data.model

import kotlinx.serialization.Serializable

enum class DotFileType {
    SHELL,
    GIT,
    VIM,
    TMUX,
    SSH,
    OTHER;

    val displayName: String
        get() = when (this) {
            SHELL -> "Shell"
            GIT -> "Git"
            VIM -> "Vim"
            TMUX -> "Tmux"
            SSH -> "SSH"
            OTHER -> "Other"
        }

    companion object {
        fun fromString(value: String?): DotFileType = when (value?.lowercase()) {
            "shell" -> SHELL
            "git" -> GIT
            "vim" -> VIM
            "tmux" -> TMUX
            "ssh" -> SSH
            else -> OTHER
        }
    }
}

@Serializable
data class DotFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: String? = null,
    val content: String? = null,
    val exists: Boolean = true,
    val writable: Boolean = true,
    val fileType: String = DotFileType.OTHER.name
) {
    val fileTypeEnum: DotFileType
        get() = DotFileType.fromString(fileType)
}

@Serializable
data class DotFileVersion(
    val id: String,
    val timestamp: String,
    val commitMessage: String? = null,
    val size: Long,
    val content: String? = null
)

@Serializable
data class DotFileTemplate(
    val name: String,
    val fileType: String,
    val description: String,
    val content: String
) {
    val fileTypeEnum: DotFileType
        get() = DotFileType.fromString(fileType)
}
