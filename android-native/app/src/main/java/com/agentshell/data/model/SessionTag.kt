package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "session_tags")
data class SessionTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String = "#2196F3",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "session_tag_assignments",
    primaryKeys = ["sessionName", "tagId"],
    foreignKeys = [ForeignKey(
        entity = SessionTag::class,
        parentColumns = ["id"],
        childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SessionTagAssignment(
    val sessionName: String,
    val tagId: String,
)
