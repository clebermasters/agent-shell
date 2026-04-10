package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.agentshell.data.model.CommandMacro
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandMacroDao {

    @Query("SELECT * FROM command_macros ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<CommandMacro>>

    @Upsert
    suspend fun upsert(macro: CommandMacro)

    @Delete
    suspend fun delete(macro: CommandMacro)
}
