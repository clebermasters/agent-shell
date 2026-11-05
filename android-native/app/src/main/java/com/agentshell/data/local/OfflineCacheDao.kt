package com.agentshell.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Embedded
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
@Entity(tableName = "cached_chat_messages", primaryKeys = ["targetKey", "messageId"], indices = [Index(value = ["targetKey", "position"]), Index(value = ["targetKey", "serverKey"])])
data class CachedChatMessage(
    val targetKey: String,
    val messageId: String,
    val position: Int,
    val payload: String,
    val syncRevision: String = "",
    val serverKey: String = "",
)

@Entity(tableName = "cached_history_sync", primaryKeys = ["targetKey"])
data class CachedHistorySync(
    val targetKey: String,
    val revision: String,
    val offset: Int = 0,
    val beforeMessageId: String? = null,
    val complete: Boolean = false,
    val keyed: Boolean = false,
    val baseRevision: String? = null,
)

/** Scheduling metadata only. Removing a task never removes its offline history. */
@Entity(tableName = "archive_sync_work", primaryKeys = ["workKey"], indices = [Index(value = ["hostId", "nextRunAt", "lastRunAt"])])
data class ArchiveSyncWork(
    val workKey: String,
    val hostId: String,
    val targetJson: String,
    val kind: String,
    val sourceHint: String = "",
    val nextRunAt: Long = 0,
    val lastRunAt: Long = 0,
    val attempts: Int = 0,
    val generation: Long = 0,
)

data class CachedServerMessage(val messageId: String, val serverKey: String)

data class CachedMessageBounds(val firstPosition: Int?, val lastPosition: Int?)

/** One read snapshot, including metadata when the saved chat is empty. */
data class CachedHistoryWindowRow(
    @Embedded val conversation: CachedConversation,
    val messageId: String?,
    val position: Int?,
    val payload: String?,
    val totalMessageCount: Int,
)

@Dao
abstract class OfflineCacheDao {
    @Query("SELECT * FROM archive_sync_work WHERE workKey = :key")
    abstract suspend fun archiveSyncWork(key: String): ArchiveSyncWork?

    @Query("SELECT * FROM archive_sync_work WHERE hostId = :hostId")
    abstract suspend fun archiveSyncWorkForHost(hostId: String): List<ArchiveSyncWork>

    @Query("SELECT * FROM archive_sync_work WHERE hostId = :hostId AND nextRunAt <= :now ORDER BY CASE WHEN workKey = :preferredKey THEN 0 ELSE 1 END, lastRunAt, nextRunAt, workKey LIMIT 1")
    abstract suspend fun nextArchiveSyncWork(hostId: String, now: Long, preferredKey: String?): ArchiveSyncWork?

    @Query("SELECT MIN(nextRunAt) FROM archive_sync_work WHERE hostId = :hostId")
    abstract suspend fun nextArchiveSyncDue(hostId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveArchiveSyncWork(work: ArchiveSyncWork)

    @Query("DELETE FROM archive_sync_work WHERE workKey = :key")
    abstract suspend fun deleteArchiveSyncWork(key: String)

    // A single SELECT gets a consistent WAL snapshot without entering Room's
    // serialized write-transaction queue. Only the requested page is decoded.
    // Keep SELECT first: Android can route a leading WITH to the write connection.
    @Query("""
        SELECT c.*, page.messageId, page.position, page.payload,
            (SELECT COUNT(*) FROM cached_chat_messages WHERE targetKey = :key) AS totalMessageCount
        FROM cached_conversations AS c LEFT JOIN (
            SELECT messageId, position, payload FROM cached_chat_messages
            WHERE targetKey = :key AND (
                COALESCE((SELECT position FROM cached_chat_messages WHERE targetKey = :key AND messageId = :beforeMessageId), :beforePosition) IS NULL
                OR position < COALESCE((SELECT position FROM cached_chat_messages WHERE targetKey = :key AND messageId = :beforeMessageId), :beforePosition)
            )
            ORDER BY position DESC LIMIT :limit
        ) AS page ON 1
        WHERE c.targetKey = :key ORDER BY page.position DESC
    """)
    abstract suspend fun historyWindow(key: String, beforePosition: Int?, beforeMessageId: String?, limit: Int): List<CachedHistoryWindowRow>

    @Query("SELECT * FROM cached_history_sync WHERE targetKey = :key")
    abstract suspend fun historySync(key: String): CachedHistorySync?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveHistorySync(state: CachedHistorySync)

    @Query("SELECT messageId,serverKey FROM cached_chat_messages WHERE targetKey = :key AND serverKey IN (:keys)")
    abstract suspend fun serverMessagesByKeys(key: String, keys: List<String>): List<CachedServerMessage>

    @Query("UPDATE cached_chat_messages SET position = :position, syncRevision = :revision, serverKey = :serverKey WHERE targetKey = :key AND messageId = :id")
    abstract suspend fun assignSyncedMessage(key: String, id: String, serverKey: String, position: Int, revision: String): Int

    @Query("UPDATE cached_chat_messages SET syncRevision = :revision WHERE targetKey = :key AND messageId IN (:ids)")
    abstract suspend fun markSyncedMessages(key: String, ids: List<String>, revision: String)

    @Query("DELETE FROM cached_chat_messages WHERE targetKey = :key AND syncRevision != :revision")
    abstract suspend fun removeUnsyncedMessages(key: String, revision: String)

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
