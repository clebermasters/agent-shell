package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.agentshell.data.model.SessionTag
import com.agentshell.data.model.SessionTagAssignment
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionTagDao {

    @Query("SELECT * FROM session_tags ORDER BY createdAt ASC")
    fun getAllTags(): Flow<List<SessionTag>>

    @Upsert
    suspend fun upsertTag(tag: SessionTag)

    @Upsert
    suspend fun upsertTags(tags: List<SessionTag>)

    @Delete
    suspend fun deleteTag(tag: SessionTag)

    @Query("SELECT tagId FROM session_tag_assignments WHERE sessionName = :sessionName")
    fun getTagIdsForSession(sessionName: String): Flow<List<String>>

    @Query("SELECT sessionName FROM session_tag_assignments WHERE tagId = :tagId")
    suspend fun getSessionsForTag(tagId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assignTag(assignment: SessionTagAssignment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assignTags(assignments: List<SessionTagAssignment>)

    @Query("DELETE FROM session_tag_assignments WHERE sessionName = :sessionName AND tagId = :tagId")
    suspend fun removeTag(sessionName: String, tagId: String)

    @Query("DELETE FROM session_tag_assignments WHERE sessionName = :sessionName")
    suspend fun clearTagsForSession(sessionName: String)

    @Query("SELECT * FROM session_tag_assignments")
    fun getAllAssignments(): Flow<List<SessionTagAssignment>>

    @Query("DELETE FROM session_tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM session_tag_assignments")
    suspend fun deleteAllAssignments()

    @Transaction
    suspend fun replaceTagsAndAssignments(
        tags: List<SessionTag>,
        assignments: List<SessionTagAssignment>,
    ) {
        val validTagIds = tags.asSequence()
            .map(SessionTag::id)
            .toSet()
        val validAssignments = assignments.filter { it.tagId in validTagIds }

        deleteAllAssignments()
        deleteAllTags()

        if (tags.isNotEmpty()) {
            upsertTags(tags)
        }
        if (validAssignments.isNotEmpty()) {
            assignTags(validAssignments)
        }
    }
}
