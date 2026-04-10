package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.agentshell.data.model.FavoriteSession
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteSessionDao {

    @Query("SELECT * FROM favorite_sessions ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<FavoriteSession>>

    @Upsert
    suspend fun upsert(favorite: FavoriteSession)

    @Delete
    suspend fun delete(favorite: FavoriteSession)

    @Query("DELETE FROM favorite_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}
