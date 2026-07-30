package org.joefang.letterbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for blob operations.
 */
@Dao
interface BlobDao {
    @Query("SELECT * FROM blobs WHERE hash = :hash")
    suspend fun getByHash(hash: String): BlobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blob: BlobEntity)

    @Query("UPDATE blobs SET ref_count = ref_count - 1 WHERE hash = :hash")
    suspend fun decrementRefCount(hash: String)

    @Query("DELETE FROM blobs WHERE hash = :hash")
    suspend fun deleteByHash(hash: String)

    /**
     * Total bytes recorded across all stored blobs, or `null` when none exist.
     *
     * `size_bytes` is written from the actual content length at ingestion, so
     * this replaces summing `File.length()` per blob: one row instead of one
     * filesystem syscall per cached email.
     */
    @Query("SELECT SUM(size_bytes) FROM blobs")
    suspend fun totalSizeBytes(): Long?

    /**
     * Every known blob hash, for reconciling the `cas/` directory against the
     * database. Bounded by the number of cached emails, and only read during a
     * reclamation sweep — never on a UI path.
     */
    @Query("SELECT hash FROM blobs")
    suspend fun allHashes(): List<String>

    @Query("DELETE FROM blobs")
    suspend fun deleteAll()
}

/**
 * Data Access Object for history item operations.
 *
 * ## No search, filter or sort here
 *
 * This DAO loads and mutates rows; it does not query. [getAllOrderedByAccess]
 * emits the whole history and
 * [org.joefang.letterbox.HistoryQuery] — a pure function — decides what to show
 * and in what order.
 *
 * That is a deliberate consequence of the list UI already holding every entry in
 * memory to render it: pushing the predicate into SQL would not shrink that
 * working set, but it would make each keystroke an asynchronous round trip
 * needing `flatMapLatest`, debouncing, one query variant per sort order, and a
 * `MATCH` expression built from untrusted input. See `docs/full-text-search.md`,
 * including the conditions under which that trade would flip.
 */
@Dao
interface HistoryItemDao {
    @Query("SELECT * FROM history_items ORDER BY last_accessed DESC")
    fun getAllOrderedByAccess(): Flow<List<HistoryItemEntity>>

    @Query("SELECT * FROM history_items WHERE id = :id")
    suspend fun getById(id: Long): HistoryItemEntity?

    /**
     * Get a single history entry by blob hash.
     * With the unique constraint on blob_hash, at most one entry exists per hash.
     */
    @Query("SELECT * FROM history_items WHERE blob_hash = :hash LIMIT 1")
    suspend fun getFirstByBlobHash(hash: String): HistoryItemEntity?

    @Insert
    suspend fun insert(item: HistoryItemEntity): Long

    @Query("UPDATE history_items SET last_accessed = :timestamp WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, timestamp: Long)

    /**
     * Rewrite a row's folded search text.
     *
     * Used to repair rows backfilled by the 3-to-4 migration, whose SQL `lower()`
     * could only fold ASCII. See `Migrations.kt`.
     */
    @Query("UPDATE history_items SET search_text = :searchText WHERE id = :id")
    suspend fun updateSearchText(id: Long, searchText: String)

    @Query("DELETE FROM history_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM history_items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM history_items WHERE blob_hash = :hash")
    suspend fun countByBlobHash(hash: String): Int

    @Query("DELETE FROM history_items")
    suspend fun deleteAll()
    
}
