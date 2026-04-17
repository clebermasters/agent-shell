package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.agentshell.data.model.FavoriteSession
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteSessionDao {

    @Query("SELECT * FROM favorite_sessions ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<FavoriteSession>>

    @Query("SELECT * FROM favorite_sessions ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<FavoriteSession>

    @Upsert
    suspend fun upsert(favorite: FavoriteSession)

    @Upsert
    suspend fun insertAll(favorites: List<FavoriteSession>)

    @Delete
    suspend fun delete(favorite: FavoriteSession)

    @Query("DELETE FROM favorite_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM favorite_sessions")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(favorites: List<FavoriteSession>) {
        deleteAll()
        if (favorites.isNotEmpty()) {
            insertAll(favorites)
        }
    }
}
