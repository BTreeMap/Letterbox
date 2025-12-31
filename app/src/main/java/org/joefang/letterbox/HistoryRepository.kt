package org.joefang.letterbox

import org.joefang.letterbox.data.BlobDao
import org.joefang.letterbox.data.BlobEntity
import org.joefang.letterbox.data.HistoryItemDao
import org.joefang.letterbox.data.HistoryItemEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public data class representing a history entry for the UI layer.
 */
data class HistoryEntry(
    val id: Long,
    val blobHash: String,
    val displayName: String,
    val originalUri: String?,
    val lastAccessed: Long
)

/**
 * Data class representing cache storage statistics.
 */
data class CacheStats(
    /** Total number of cached email entries. */
    val entryCount: Int,
    /** Total size of cached blobs in bytes. */
    val totalSizeBytes: Long
)

/**
 * Repository for managing email file history with Content-Addressable Storage (CAS).
 * 
 * Features:
 * - Deduplication: Same file content is stored only once
 * - Indefinite caching: Emails are cached until user explicitly clears them
 * - Persistence: Uses Room database for metadata and file system for blobs
 */
class HistoryRepository(
    private val baseDir: File,
    private val blobDao: BlobDao,
    private val historyItemDao: HistoryItemDao
) {
    private val casDir: File = File(baseDir, "cas").also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    init {
        // Load initial items from database
        scope.launch {
            historyItemDao.getAllOrderedByAccess().collect { entities ->
                _items.value = entities.map { it.toHistoryEntry() }
            }
        }
    }

    /**
     * Ingest a new email file into the repository.
     * - Computes SHA-256 hash of the content
     * - Stores the file in CAS if not already present
     * - Creates a history entry
     * 
     * Emails are cached indefinitely until user explicitly clears them.
     */
    suspend fun ingest(bytes: ByteArray, displayName: String, originalUri: String?): HistoryEntry {
        return withContext(Dispatchers.IO) {
            val hash = sha256(bytes)
            val blobFile = File(casDir, hash)
            
            // Check if blob already exists
            val existingBlob = blobDao.getByHash(hash)
            if (existingBlob == null) {
                // New content - save to file system and database
                blobFile.writeBytes(bytes)
                blobDao.insert(BlobEntity(hash, bytes.size.toLong(), 1))
            } else {
                // Content exists - increment ref count
                blobDao.incrementRefCount(hash)
            }

            // Create history entry
            val now = System.currentTimeMillis()
            val entity = HistoryItemEntity(
                blobHash = hash,
                displayName = displayName.ifBlank { "Untitled" },
                originalUri = originalUri,
                lastAccessed = now
            )
            val id = historyItemDao.insert(entity)

            entity.copy(id = id).toHistoryEntry()
        }
    }

    /**
     * Update the last accessed timestamp for an entry.
     */
    suspend fun access(entryId: Long): HistoryEntry? {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            historyItemDao.updateLastAccessed(entryId, now)
            historyItemDao.getById(entryId)?.toHistoryEntry()
        }
    }

    /**
     * Get the file for a blob by its hash.
     */
    fun blobFor(hash: String): File? {
        val file = File(casDir, hash)
        return if (file.exists()) file else null
    }

    /**
     * Get blob metadata by hash.
     */
    suspend fun blobMeta(hash: String): BlobEntity? {
        return blobDao.getByHash(hash)
    }
    
    /**
     * Delete a single history entry by ID.
     */
    suspend fun delete(entryId: Long) {
        withContext(Dispatchers.IO) {
            val entry = historyItemDao.getById(entryId) ?: return@withContext
            
            // Delete history item
            historyItemDao.deleteById(entryId)
            
            // Check if blob is still referenced
            val refCount = historyItemDao.countByBlobHash(entry.blobHash)
            if (refCount == 0) {
                // No more references - delete blob
                blobDao.deleteByHash(entry.blobHash)
                File(casDir, entry.blobHash).delete()
            } else {
                // Update ref count
                blobDao.decrementRefCount(entry.blobHash)
            }
        }
    }
    
    /**
     * Clear all history entries.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            // Get all blobs before clearing
            val allItems = historyItemDao.getRecentItems(Int.MAX_VALUE)
            val blobHashes = allItems.map { it.blobHash }.distinct()
            
            // Clear database
            allItems.forEach { historyItemDao.deleteById(it.id) }
            
            // Delete all blob files
            blobHashes.forEach { hash ->
                blobDao.deleteByHash(hash)
                File(casDir, hash).delete()
            }
        }
    }

    /**
     * Get cache statistics including total size and entry count.
     * Calculates actual size by summing blob file sizes.
     */
    suspend fun getCacheStats(): CacheStats {
        return withContext(Dispatchers.IO) {
            val entryCount = historyItemDao.count()
            
            // Calculate total size by summing unique blob sizes
            val allItems = historyItemDao.getRecentItems(Int.MAX_VALUE)
            val uniqueHashes = allItems.map { it.blobHash }.distinct()
            var totalSize = 0L
            
            for (hash in uniqueHashes) {
                val blobFile = File(casDir, hash)
                if (blobFile.exists()) {
                    totalSize += blobFile.length()
                }
            }
            
            CacheStats(entryCount, totalSize)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun HistoryItemEntity.toHistoryEntry() = HistoryEntry(
        id = id,
        blobHash = blobHash,
        displayName = displayName,
        originalUri = originalUri,
        lastAccessed = lastAccessed
    )
}

/**
 * In-memory implementation of HistoryRepository for testing and simple usage.
 * Does not require Room database.
 * 
 * Emails are cached indefinitely until user explicitly clears them.
 */
class InMemoryHistoryRepository(
    private val baseDir: File
) {
    private val casDir: File = File(baseDir, "cas").also { it.mkdirs() }
    private val blobs = mutableMapOf<String, BlobMeta>()
    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    data class BlobMeta(
        val hash: String,
        val sizeBytes: Long,
        val refCount: Int
    )

    @Synchronized
    fun ingest(bytes: ByteArray, displayName: String, originalUri: String?): HistoryEntry {
        val hash = sha256(bytes)
        val blobFile = File(casDir, hash)
        val existingMeta = blobs[hash]
        if (existingMeta == null) {
            blobFile.writeBytes(bytes)
            blobs[hash] = BlobMeta(hash, bytes.size.toLong(), 1)
        } else {
            blobs[hash] = existingMeta.copy(refCount = existingMeta.refCount + 1)
        }

        val newEntry = HistoryEntry(
            id = nextId(),
            blobHash = hash,
            displayName = displayName.ifBlank { "Untitled" },
            originalUri = originalUri,
            lastAccessed = System.currentTimeMillis()
        )
        _items.value = (_items.value + newEntry).sortedByDescending { it.lastAccessed }
        return newEntry
    }

    @Synchronized
    fun access(entryId: Long): HistoryEntry? {
        val updated = _items.value.map { entry ->
            if (entry.id == entryId) entry.copy(lastAccessed = System.currentTimeMillis()) else entry
        }.sortedByDescending { it.lastAccessed }
        _items.value = updated
        return updated.firstOrNull { it.id == entryId }
    }

    fun blobFor(hash: String): File? = blobs[hash]?.let { File(casDir, it.hash) }

    fun blobMeta(hash: String): BlobMeta? = blobs[hash]

    /**
     * Delete a single history entry by ID.
     */
    @Synchronized
    fun delete(entryId: Long) {
        val entry = _items.value.find { it.id == entryId } ?: return
        val remaining = _items.value.filter { it.id != entryId }
        _items.value = remaining
        
        // Check if blob is still referenced
        val remainingRefs = remaining.count { it.blobHash == entry.blobHash }
        if (remainingRefs == 0) {
            blobs.remove(entry.blobHash)
            File(casDir, entry.blobHash).delete()
        } else {
            blobs[entry.blobHash]?.let { meta ->
                blobs[entry.blobHash] = meta.copy(refCount = remainingRefs)
            }
        }
    }

    /**
     * Clear all history entries.
     */
    @Synchronized
    fun clearAll() {
        _items.value = emptyList()
        // Delete all blob files
        blobs.keys.toList().forEach { hash ->
            File(casDir, hash).delete()
        }
        blobs.clear()
    }

    /**
     * Get cache statistics including total size and entry count.
     */
    @Synchronized
    fun getCacheStats(): CacheStats {
        val entryCount = _items.value.size
        val totalSize = blobs.values.sumOf { it.sizeBytes }
        return CacheStats(entryCount, totalSize)
    }

    private fun nextId(): Long = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

