package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.DefaultReciters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ReciterEntity::class,
        BookmarkEntity::class,
        DownloadEntity::class,
        SurahStateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class QuranDatabase : RoomDatabase() {

    abstract fun reciterDao(): ReciterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao
    abstract fun surahStateDao(): SurahStateDao

    companion object {
        @Volatile
        private var INSTANCE: QuranDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // One-time cleanup of deprecated incomplete reciter entry without touching user data
                db.execSQL("DELETE FROM reciters WHERE id = 'w_dlaimi' AND isCustom = 0")
            }
        }

        fun getDatabase(context: Context): QuranDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QuranDatabase::class.java,
                    "quran_audio_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val initialReciters = DefaultReciters.list.map {
                            ReciterEntity.fromReciter(it.copy(isCustom = false))
                        }
                        database.reciterDao().upsertDefaultReciters(initialReciters)
                    }
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        // Sync built-in default reciters cleanly on updates without altering custom reciters
                        val defaultReciters = DefaultReciters.list.map {
                            ReciterEntity.fromReciter(it.copy(isCustom = false))
                        }
                        database.reciterDao().upsertDefaultReciters(defaultReciters)
                    }
                }
            }
        }
    }
}
