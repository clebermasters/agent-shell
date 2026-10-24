package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_sessions", primaryKeys = ["hostId", "kind", "sessionId"])
data class CachedSession(
    val hostId: String,
    val kind: String,
    val sessionId: String,
    val position: Int,
    val payload: String,
)

@Entity(tableName = "cached_conversations", primaryKeys = ["targetKey"])
data class CachedConversation(
    val targetKey: String,
    val conversationKey: String = "",
    val tool: String? = null,
    val cwd: String = "",
    val complete: Boolean = false,
)

// One row per message avoids putting an entire unlimited history in a CursorWindow blob.
@Entity(tableName = "cached_chat_messages", primaryKeys = ["targetKey", "messageId"], indices = [Index(value = ["targetKey", "position"])])
data class CachedChatMessage(
    val targetKey: String,
    val messageId: String,
    val position: Int,
    val payload: String,
)

data class CachedMessageBounds(val firstPosition: Int?, val lastPosition: Int?)

@Dao
abstract class OfflineCacheDao {
    @Query("SELECT * FROM cached_sessions WHERE hostId = :hostId ORDER BY kind, position")
    abstract fun observeSessions(hostId: String): Flow<List<CachedSession>>

    @Query("DELETE FROM cached_sessions WHERE hostId = :hostId AND kind = :kind")
    abstract suspend fun deleteSessions(hostId: String, kind: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSessions(rows: List<CachedSession>)

    @Transaction
    open suspend fun replaceSessions(hostId: String, kind: String, rows: List<CachedSession>) {
        deleteSessions(hostId, kind)
        insertSessions(rows)
    }

    @Query("SELECT * FROM cached_conversations WHERE targetKey = :key")
    abstract suspend fun conversation(key: String): CachedConversation?

    @Query("SELECT * FROM cached_chat_messages WHERE targetKey = :key ORDER BY position")
    abstract suspend fun messages(key: String): List<CachedChatMessage>

    @Query("SELECT * FROM cached_chat_messages WHERE targetKey = :key AND messageId IN (:ids)")
    abstract suspend fun messagesByIds(key: String, ids: List<String>): List<CachedChatMessage>

    @Query("SELECT position FROM cached_chat_messages WHERE targetKey = :key ORDER BY position ASC LIMIT 1")
    abstract suspend fun firstMessagePosition(key: String): Int?

    @Query("SELECT position FROM cached_chat_messages WHERE targetKey = :key ORDER BY position DESC LIMIT 1")
    abstract suspend fun lastMessagePosition(key: String): Int?

    open suspend fun messageBounds(key: String) = CachedMessageBounds(firstMessagePosition(key), lastMessagePosition(key))

    @Query("SELECT messageId FROM cached_chat_messages WHERE targetKey = :key ORDER BY position DESC LIMIT 1")
    abstract suspend fun latestMessageId(key: String): String?

    @Query("SELECT position FROM cached_chat_messages WHERE targetKey = :key AND messageId = :id")
    abstract suspend fun messagePosition(key: String, id: String): Int?

    @Query("SELECT COUNT(*) FROM cached_chat_messages WHERE targetKey = :key")
    abstract suspend fun messageCount(key: String): Int

    @Query("SELECT * FROM cached_chat_messages WHERE targetKey = :key ORDER BY position DESC LIMIT :limit")
    abstract suspend fun latestMessages(key: String, limit: Int): List<CachedChatMessage>

    @Query("SELECT * FROM cached_chat_messages WHERE targetKey = :key AND position < :beforePosition ORDER BY position DESC LIMIT :limit")
    abstract suspend fun messagesBefore(key: String, beforePosition: Int, limit: Int): List<CachedChatMessage>

    open suspend fun messagePage(key: String, beforePosition: Int?, limit: Int) =
        if (beforePosition == null) latestMessages(key, limit) else messagesBefore(key, beforePosition, limit)

    @Query("UPDATE cached_chat_messages SET position = position + :amount WHERE targetKey = :key AND position >= :fromPosition")
    abstract suspend fun shiftMessages(key: String, fromPosition: Int, amount: Int)

    @Query("SELECT messageId FROM cached_chat_messages WHERE targetKey = :key")
    abstract suspend fun messageIds(key: String): List<String>

    @Query("DELETE FROM cached_chat_messages WHERE targetKey = :key AND messageId IN (:ids)")
    abstract suspend fun deleteMessagesByIds(key: String, ids: List<String>)

    @Query("DELETE FROM cached_chat_messages WHERE targetKey = :key AND position < :firstPosition")
    abstract suspend fun deleteBeforePosition(key: String, firstPosition: Int)

    @Query("DELETE FROM cached_chat_messages WHERE targetKey = :key AND position BETWEEN :firstPosition AND :lastPosition AND messageId NOT IN (:ids)")
    abstract suspend fun deleteMissingInPage(key: String, firstPosition: Int, lastPosition: Int, ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertConversation(row: CachedConversation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessages(rows: List<CachedChatMessage>)

    @Query("DELETE FROM cached_chat_messages WHERE targetKey = :key")
    abstract suspend fun deleteMessages(key: String)

    @Transaction
    open suspend fun replaceHistory(conversation: CachedConversation, rows: List<CachedChatMessage>) {
        deleteMessages(conversation.targetKey)
        insertMessages(rows)
        insertConversation(conversation)
    }
}
