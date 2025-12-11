package com.btreemap.letterbox.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the Letterbox app.
 * Contains tables for blobs (CAS) and history items.
 */
@Database(
    entities = [BlobEntity::class, HistoryItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LetterboxDatabase : RoomDatabase() {
    abstract fun blobDao(): BlobDao
    abstract fun historyItemDao(): HistoryItemDao

    companion object {
        @Volatile
        private var INSTANCE: LetterboxDatabase? = null

        fun getInstance(context: Context): LetterboxDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LetterboxDatabase::class.java,
                    "letterbox.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
