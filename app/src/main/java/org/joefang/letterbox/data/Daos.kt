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

    @Query("UPDATE blobs SET ref_count = ref_count + 1 WHERE hash = :hash")
    suspend fun incrementRefCount(hash: String)

    @Query("UPDATE blobs SET ref_count = ref_count - 1 WHERE hash = :hash")
    suspend fun decrementRefCount(hash: String)

    @Query("DELETE FROM blobs WHERE hash = :hash")
    suspend fun deleteByHash(hash: String)

    @Query("SELECT * FROM blobs WHERE ref_count <= 0")
    suspend fun getOrphanedBlobs(): List<BlobEntity>
}

/**
 * Data Access Object for history item operations.
 * 
 * ## Search, Filter, and Sort Design
 * 
 * This DAO supports comprehensive search, filter, and sort functionality:
 * 
 * ### Full-Text Search
 * Uses SQLite FTS4 for fast, accurate text search across email content.
 * The `searchEmails` query joins the FTS table for matching.
 * 
 * ### Filters
 * - Date range filtering using email_date column
 * - Has attachments filter
 * - Sender contains filter (partial match)
 * 
 * ### Sorting
 * - By date (newest/oldest first)
 * - By subject (A-Z / Z-A)
 * - By sender (A-Z / Z-A)
 * 
 * Due to Room's limitations with dynamic ORDER BY, we use @RawQuery for
 * complex search/filter/sort combinations.
 */
@Dao
interface HistoryItemDao {
    @Query("SELECT * FROM history_items ORDER BY last_accessed DESC")
    fun getAllOrderedByAccess(): Flow<List<HistoryItemEntity>>

    @Query("SELECT * FROM history_items ORDER BY last_accessed DESC LIMIT :limit")
    suspend fun getRecentItems(limit: Int): List<HistoryItemEntity>

    @Query("SELECT * FROM history_items WHERE id = :id")
    suspend fun getById(id: Long): HistoryItemEntity?

    @Query("SELECT * FROM history_items WHERE blob_hash = :hash")
    suspend fun getByBlobHash(hash: String): List<HistoryItemEntity>

    @Insert
    suspend fun insert(item: HistoryItemEntity): Long

    @Query("UPDATE history_items SET last_accessed = :timestamp WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, timestamp: Long)

    @Query("DELETE FROM history_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM history_items")
    suspend fun count(): Int

    @Query("SELECT * FROM history_items ORDER BY last_accessed ASC LIMIT :count")
    suspend fun getOldestItems(count: Int): List<HistoryItemEntity>

    @Query("SELECT COUNT(*) FROM history_items WHERE blob_hash = :hash")
    suspend fun countByBlobHash(hash: String): Int

    @Transaction
    @Query("DELETE FROM history_items WHERE id IN (SELECT id FROM history_items ORDER BY last_accessed ASC LIMIT :count)")
    suspend fun deleteOldestItems(count: Int)

    @Query("DELETE FROM history_items")
    suspend fun deleteAll()
    
    // =====================================================================
    // Search, Filter, and Sort Queries
    // =====================================================================
    
    /**
     * Full-text search across email content using FTS4.
     * 
     * Searches across: subject, sender_email, sender_name, recipient_emails,
     * recipient_names, and body_preview.
     * 
     * @param query The search query (FTS4 match syntax supported)
     * @return List of matching history items ordered by relevance then date
     */
    @Query("""
        SELECT history_items.* FROM history_items
        JOIN email_fts ON history_items.rowid = email_fts.rowid
        WHERE email_fts MATCH :query
        ORDER BY 
            CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END DESC
    """)
    suspend fun searchEmails(query: String): List<HistoryItemEntity>
    
    /**
     * Full-text search with reactive Flow for UI updates.
     */
    @Query("""
        SELECT history_items.* FROM history_items
        JOIN email_fts ON history_items.rowid = email_fts.rowid
        WHERE email_fts MATCH :query
        ORDER BY 
            CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END DESC
    """)
    fun searchEmailsFlow(query: String): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by date (newest first).
     * Uses email_date with fallback to last_accessed for emails with unparseable dates.
     */
    @Query("""
        SELECT * FROM history_items 
        ORDER BY CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END DESC
    """)
    fun getAllByDateDesc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by date (oldest first).
     */
    @Query("""
        SELECT * FROM history_items 
        ORDER BY CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END ASC
    """)
    fun getAllByDateAsc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by subject (A-Z).
     */
    @Query("SELECT * FROM history_items ORDER BY subject COLLATE NOCASE ASC")
    fun getAllBySubjectAsc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by subject (Z-A).
     */
    @Query("SELECT * FROM history_items ORDER BY subject COLLATE NOCASE DESC")
    fun getAllBySubjectDesc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by sender (A-Z).
     * Uses sender_name with fallback to sender_email.
     */
    @Query("""
        SELECT * FROM history_items 
        ORDER BY 
            CASE WHEN sender_name != '' THEN sender_name ELSE sender_email END 
            COLLATE NOCASE ASC
    """)
    fun getAllBySenderAsc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Get all items sorted by sender (Z-A).
     */
    @Query("""
        SELECT * FROM history_items 
        ORDER BY 
            CASE WHEN sender_name != '' THEN sender_name ELSE sender_email END 
            COLLATE NOCASE DESC
    """)
    fun getAllBySenderDesc(): Flow<List<HistoryItemEntity>>
    
    /**
     * Filter items that have attachments.
     */
    @Query("SELECT * FROM history_items WHERE has_attachments = 1 ORDER BY last_accessed DESC")
    fun getWithAttachments(): Flow<List<HistoryItemEntity>>
    
    /**
     * Filter items by date range (inclusive).
     * Uses email_date with fallback to last_accessed.
     */
    @Query("""
        SELECT * FROM history_items 
        WHERE CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END 
              BETWEEN :fromDate AND :toDate
        ORDER BY CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END DESC
    """)
    fun getByDateRange(fromDate: Long, toDate: Long): Flow<List<HistoryItemEntity>>
    
    /**
     * Filter items by sender (partial match).
     */
    @Query("""
        SELECT * FROM history_items 
        WHERE sender_email LIKE '%' || :senderQuery || '%' 
           OR sender_name LIKE '%' || :senderQuery || '%'
        ORDER BY last_accessed DESC
    """)
    fun getBySender(senderQuery: String): Flow<List<HistoryItemEntity>>
    
    /**
     * Dynamic query for complex search, filter, and sort combinations.
     * Used when multiple filters are applied simultaneously.
     */
    @RawQuery(observedEntities = [HistoryItemEntity::class])
    fun searchWithFilters(query: SupportSQLiteQuery): Flow<List<HistoryItemEntity>>
    
    /**
     * Suspend version of dynamic query for one-shot operations.
     */
    @RawQuery
    suspend fun searchWithFiltersOnce(query: SupportSQLiteQuery): List<HistoryItemEntity>
}
