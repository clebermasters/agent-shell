package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "command_macros")
data class CommandMacro(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
