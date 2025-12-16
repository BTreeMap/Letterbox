package org.joefang.letterbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
}
