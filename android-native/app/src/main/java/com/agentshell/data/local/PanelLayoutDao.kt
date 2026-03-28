package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.agentshell.data.model.PanelEntity
import com.agentshell.data.model.PanelLayoutEntity
import com.agentshell.data.model.PanelLayoutWithPanels
import kotlinx.coroutines.flow.Flow

@Dao
interface PanelLayoutDao {

    @Transaction
    @Query("SELECT * FROM panel_layouts ORDER BY lastUsedAt DESC")
    fun getAllWithPanels(): Flow<List<PanelLayoutWithPanels>>

    @Transaction
    @Query("SELECT * FROM panel_layouts WHERE id = :id")
    suspend fun getByIdWithPanels(id: String): PanelLayoutWithPanels?

    @Upsert
    suspend fun insertLayout(layout: PanelLayoutEntity)

    @Upsert
    suspend fun insertPanels(panels: List<PanelEntity>)

    @Query("DELETE FROM panels WHERE layoutId = :layoutId")
    suspend fun deletePanelsByLayout(layoutId: String)

    @Query("DELETE FROM panel_layouts WHERE id = :id")
    suspend fun deleteLayout(id: String)

    @Query("UPDATE panel_layouts SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)
}
