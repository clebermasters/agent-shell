package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "favorite_sessions")
data class FavoriteSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
