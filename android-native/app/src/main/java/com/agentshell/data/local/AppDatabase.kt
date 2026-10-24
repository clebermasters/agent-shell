package com.agentshell.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentshell.data.model.CommandMacro
import com.agentshell.data.model.FavoriteSession
import com.agentshell.data.model.Host
import com.agentshell.data.model.PanelEntity
import com.agentshell.data.model.PanelLayoutEntity
import com.agentshell.data.model.SessionTag
import com.agentshell.data.model.SessionTagAssignment

@Database(
    entities = [
        Host::class,
        CachedSession::class,
        CachedConversation::class,
        CachedChatMessage::class,
        PanelLayoutEntity::class,
        PanelEntity::class,
        FavoriteSession::class,
        CommandMacro::class,
        SessionTag::class,
        SessionTagAssignment::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao
    abstract fun hostDao(): HostDao
    abstract fun panelLayoutDao(): PanelLayoutDao
    abstract fun favoriteSessionDao(): FavoriteSessionDao
    abstract fun commandMacroDao(): CommandMacroDao
    abstract fun sessionTagDao(): SessionTagDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_chat_messages_targetKey_position ON cached_chat_messages (targetKey, position)")
                // Version 9 could mark a partial refresh complete despite a gap.
                // Retain every saved row and revalidate continuity once in the background.
                db.execSQL("UPDATE cached_conversations SET complete = 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS cached_sessions (hostId TEXT NOT NULL, kind TEXT NOT NULL, sessionId TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(hostId, kind, sessionId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS cached_conversations (targetKey TEXT NOT NULL PRIMARY KEY, conversationKey TEXT NOT NULL, tool TEXT, cwd TEXT NOT NULL, complete INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cached_chat_messages (targetKey TEXT NOT NULL, messageId TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(targetKey, messageId))")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN tls INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS panel_layouts (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        panelCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS panels (
                        id TEXT NOT NULL PRIMARY KEY,
                        layoutId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        panelType TEXT NOT NULL,
                        sessionName TEXT NOT NULL,
                        windowIndex INTEGER NOT NULL DEFAULT 0,
                        isAcp INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (layoutId) REFERENCES panel_layouts(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_panels_layoutId ON panels(layoutId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        path TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_sessions ADD COLUMN startupCommand TEXT")
                db.execSQL("ALTER TABLE favorite_sessions ADD COLUMN startupArgs TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS command_macros (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        command TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS session_tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        colorHex TEXT NOT NULL DEFAULT '#2196F3',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS session_tag_assignments (
                        sessionName TEXT NOT NULL,
                        tagId TEXT NOT NULL,
                        PRIMARY KEY (sessionName, tagId),
                        FOREIGN KEY (tagId) REFERENCES session_tags(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }
    }
}
