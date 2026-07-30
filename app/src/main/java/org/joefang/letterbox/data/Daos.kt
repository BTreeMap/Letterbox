package org.joefang.letterbox.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.joefang.letterbox.CacheStats

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
 * ## Querying
 *
 * There is one query, [pagingSource], and its statement is rendered by
 * [org.joefang.letterbox.HistoryQuery.toSqlSelect]. Nothing here loads the whole
 * table: the list is paged, so memory tracks the visible window rather than the
 * size of the cache, which never evicts.
 *
 * [page] is the one deliberate full walk, used by export, and it streams.
 *
 * See `docs/full-text-search.md`.
 */
@Dao
interface HistoryItemDao {
    /**
     * A page source for the rows matching a rendered [org.joefang.letterbox.HistoryQuery].
     *
     * `@RawQuery` because the `WHERE` and `ORDER BY` vary: Room cannot express a
     * dynamic `ORDER BY`, and enumerating the twelve combinations as separate
     * queries is how the retired layer ended up with six `getAllBy*` methods.
     * The statement is not assembled from user input — the needle is bound and
     * every interpolated fragment comes from eliminating a closed enum.
     *
     * [observedEntities] is what makes the returned source invalidate itself when
     * `history_items` changes; a raw query gives Room nothing else to infer that
     * from, and without it the list would silently stop updating on insert or
     * delete.
     */
    @RawQuery(observedEntities = [HistoryItemEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, HistoryItemEntity>

    /**
     * Entry count and total cached bytes, re-emitted whenever either changes.
     *
     * Two correlated sub-selects returning a single row, so this costs the same
     * whether the cache holds ten emails or a hundred thousand. Room derives the
     * tables to observe from the SQL, so both `history_items` and `blobs` are
     * watched.
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM history_items) AS entryCount,
               (SELECT COALESCE(SUM(size_bytes), 0) FROM blobs) AS totalSizeBytes
        """
    )
    fun cacheStats(): Flow<CacheStats>

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

    /**
     * One page of history rows, ordered by `id`.
     *
     * Ordered by the primary key rather than by access time so that walking the
     * whole table page by page is stable: `last_accessed` changes while a long
     * walk is in progress, which would let rows shift between pages and be
     * visited twice or skipped. Used by export.
     */
    @Query("SELECT * FROM history_items ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<HistoryItemEntity>

    @Query("SELECT COUNT(*) FROM history_items WHERE blob_hash = :hash")
    suspend fun countByBlobHash(hash: String): Int

    @Query("DELETE FROM history_items")
    suspend fun deleteAll()
    
}
