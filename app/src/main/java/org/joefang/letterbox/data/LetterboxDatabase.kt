package org.joefang.letterbox.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the Letterbox app.
 * 
 * ## Tables
 * 
 * - **blobs**: Content-Addressable Storage (CAS) for email file data
 * - **history_items**: Email history entries with metadata for search/filter/sort
 * - **email_fts**: FTS4 virtual table for full-text search (auto-synced with history_items)
 * 
 * ## Version History
 * 
 * - **Version 1**: Initial schema with blobs and history_items
 * - **Version 2**: Added email metadata fields (subject, sender, recipient, date, etc.)
 *                  and FTS4 table for full-text search. This is a breaking change -
 *                  database is dropped and recreated since the app is pre-beta.
 * - **Version 3**: Added unique constraint on blob_hash to enforce deduplication.
 *                  Each unique EML file (by SHA-256 checksum) now has exactly one history entry.
 * 
 * ## Migration Strategy
 * 
 * Since the app is pre-beta, we use `fallbackToDestructiveMigration()` instead of
 * writing migration scripts. This means existing cached emails will be lost on update,
 * which is acceptable for early development.
 */
@Database(
    entities = [BlobEntity::class, HistoryItemEntity::class, EmailFtsEntity::class],
    version = 3,
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
                )
                    // Pre-beta: destructive migration is acceptable
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Clear the singleton instance. Used for testing.
         */
        fun clearInstance() {
            INSTANCE = null
        }
    }
}
