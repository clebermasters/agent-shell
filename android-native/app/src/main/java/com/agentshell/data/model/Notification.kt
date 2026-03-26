package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationFile(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long
)

/**
 * Named AppNotification to avoid clash with android.app.Notification.
 */
@Serializable
data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val source: String,
    val sourceDetail: String? = null,
    val timestamp: Long,
    val read: Boolean,
    val fileCount: Int,
    val files: List<NotificationFile>? = null
)
