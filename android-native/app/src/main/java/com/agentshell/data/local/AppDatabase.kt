package com.agentshell.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentshell.data.model.Host
import com.agentshell.data.model.PanelEntity
import com.agentshell.data.model.PanelLayoutEntity

@Database(
    entities = [Host::class, PanelLayoutEntity::class, PanelEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun panelLayoutDao(): PanelLayoutDao

    companion object {
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
    }
}
