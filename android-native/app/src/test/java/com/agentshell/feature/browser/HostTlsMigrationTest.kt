package com.agentshell.feature.browser

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.agentshell.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class HostTlsMigrationTest {
    @Test fun migrationPreservesExistingServerRowsAndProtocolInference() {
        val context=RuntimeEnvironment.getApplication()
        val name="browser-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("CREATE TABLE hosts(id TEXT PRIMARY KEY,name TEXT,address TEXT,port INTEGER,lastConnected INTEGER,sortOrder INTEGER)")
            db.execSQL("INSERT INTO hosts VALUES('existing','My server','example.test',443,123,2)")
            db.version=7
        }
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object:SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db:SupportSQLiteDatabase) { error("Existing database was replaced") }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) { AppDatabase.MIGRATION_7_8.migrate(db) }
            }).build())
        helper.use { db -> db.writableDatabase.query("SELECT id,name,port,lastConnected,sortOrder,tls FROM hosts").use { cursor ->
            assertTrue(cursor.moveToFirst());assertEquals("existing",cursor.getString(0));assertEquals("My server",cursor.getString(1))
            assertEquals(443,cursor.getInt(2));assertEquals(123L,cursor.getLong(3));assertEquals(2,cursor.getInt(4));assertTrue(cursor.isNull(5))
        } }
        context.deleteDatabase(name)
    }
}
