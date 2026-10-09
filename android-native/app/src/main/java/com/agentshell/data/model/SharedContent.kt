package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class IncomingShare(
    val id: String,
    val text: String = "",
    val uris: List<String> = emptyList(),
    val mimeType: String? = null,
)

@Serializable
data class SharedAttachment(
    val uri: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Serializable
data class SharedDraft(
    val id: String,
    val text: String = "",
    val attachment: SharedAttachment? = null,
    val filenames: List<String> = emptyList(),
)
