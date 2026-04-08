package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.agentshell.data.model.Host
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Host?

    @Upsert
    suspend fun insert(host: Host)

    @Delete
    suspend fun delete(host: Host)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: String)
}
