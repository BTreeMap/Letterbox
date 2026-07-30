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
 * - **history_items**: Email history entries, including the folded `search_text`
 *   column that search matches against
 * 
 * ## Version History
 * 
 * - **Version 1**: Initial schema with blobs and history_items
 * - **Version 2**: Added email metadata fields (subject, sender, recipient, date, etc.)
 *                  and FTS4 table for full-text search. This is a breaking change -
 *                  database is dropped and recreated since the app is pre-beta.
 * - **Version 3**: Added unique constraint on blob_hash to enforce deduplication.
 *                  Each unique EML file (by SHA-256 checksum) now has exactly one history entry.
 * - **Version 4**: Dropped the never-queried `email_fts` FTS4 table and its
 *                  content-sync triggers; added the `search_text` column that
 *                  search matches against. Migrated by `MIGRATION_3_4`, so no
 *                  cached email is lost.
 * 
 * ## Migration Strategy
 *
 * `fallbackToDestructiveMigration()` is still the fallback, which means any schema
 * change without an explicit `Migration` **destroys every user's cached email**.
 * That makes an untested migration a silent-data-loss bug, so schemas are
 * exported and migrations are tested.
 *
 * ## Schema export
 *
 * `exportSchema = true` writes a JSON description of each version to
 * `app/schemas/`, and those files are committed. Room's `MigrationTestHelper`
 * needs the JSON for the *starting* version in order to create a database at
 * that version, so a schema can only be tested against a predecessor whose JSON
 * was captured **before** the change. Committing them is therefore not
 * housekeeping: it is the only thing that keeps the next migration testable.
 *
 * When changing any `@Entity`: bump [version], write a `Migration`, add a case to
 * `LetterboxDatabaseMigrationTest`, and commit the regenerated `app/schemas/`
 * alongside the change.
 */
@Database(
    entities = [BlobEntity::class, HistoryItemEntity::class],
    version = 4,
    exportSchema = true
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
                    .addMigrations(MIGRATION_3_4)
                    // Only reached for version steps with no migration above.
                    // It deletes the user's cached email, so every schema change
                    // must add a migration rather than rely on this.
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
