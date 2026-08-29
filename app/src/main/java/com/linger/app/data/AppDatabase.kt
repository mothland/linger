package com.irin.flowlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.irin.flowlog.data.dao.EventDao
import com.irin.flowlog.data.dao.SessionDao
import com.irin.flowlog.data.entity.EventEntity
import com.irin.flowlog.data.entity.SessionEntity

// @Database tells Room which entities (tables) exist and what the schema version is.
// If you ever add/change a column, you must increment version and provide a Migration — see DECISIONS.md.
// exportSchema = true writes a JSON schema file to app/schemas/ so you can track schema changes in git.
@Database(
    entities = [EventEntity::class, SessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sessionDao(): SessionDao

    companion object {
        // Adds postViewId column — nullable, defaults to NULL for all existing rows.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN postViewId TEXT DEFAULT NULL")
            }
        }

        // @Volatile ensures that reads/writes to `instance` are always visible to all threads.
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // Double-checked locking: fast path skips the lock if instance is already set.
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,   // use applicationContext to avoid Activity leaks
                    AppDatabase::class.java,
                    "flowlog.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
            }
        }
    }
}
