package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "panels",
    foreignKeys = [
        ForeignKey(
            entity = PanelLayoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["layoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("layoutId")],
)
data class PanelEntity(
    @PrimaryKey val id: String,
    val layoutId: String,
    val position: Int,
    val panelType: String,
    val sessionName: String,
    val windowIndex: Int = 0,
    val isAcp: Boolean = false,
)
