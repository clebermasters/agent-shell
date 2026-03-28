package com.agentshell.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "panel_layouts")
data class PanelLayoutEntity(
    @PrimaryKey val id: String,
    val name: String,
    val panelCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)

data class PanelLayoutWithPanels(
    @Embedded val layout: PanelLayoutEntity,
    @Relation(parentColumn = "id", entityColumn = "layoutId")
    val panels: List<PanelEntity>,
)
