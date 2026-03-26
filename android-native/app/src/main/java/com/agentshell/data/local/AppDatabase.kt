package com.agentshell.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.agentshell.data.model.Host

@Database(
    entities = [Host::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
}
