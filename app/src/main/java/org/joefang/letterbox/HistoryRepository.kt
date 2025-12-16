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
 * Repository for managing email file history with Content-Addressable Storage (CAS).
 * 
 * Features:
 * - Deduplication: Same file content is stored only once
 * - LRU eviction: Oldest entries are removed when limit is exceeded
 * - Persistence: Uses Room database for metadata and file system for blobs
 */
class HistoryRepository(
    private val baseDir: File,
    private val blobDao: BlobDao,
    private val historyItemDao: HistoryItemDao,
    private val historyLimit: Int = 10
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
     * - Enforces the history limit by evicting oldest items
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

            // Enforce history limit
            enforceLimit()

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
     * Enforce the history limit by removing oldest entries.
     */
    private suspend fun enforceLimit() {
        if (historyLimit <= 0) return
        
        val count = historyItemDao.count()
        if (count <= historyLimit) return

        val toRemove = count - historyLimit
        val oldestItems = historyItemDao.getOldestItems(toRemove)
        
        for (item in oldestItems) {
            // Delete history item
            historyItemDao.deleteById(item.id)
            
            // Check if blob is still referenced
            val refCount = historyItemDao.countByBlobHash(item.blobHash)
            if (refCount == 0) {
                // No more references - delete blob
                blobDao.deleteByHash(item.blobHash)
                File(casDir, item.blobHash).delete()
            } else {
                // Update ref count
                blobDao.decrementRefCount(item.blobHash)
            }
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
 */
class InMemoryHistoryRepository(
    private val baseDir: File,
    private val historyLimit: Int = 10
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
        enforceLimit()
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

    private fun enforceLimit() {
        if (historyLimit <= 0) return
        val items = _items.value
        if (items.size <= historyLimit) return

        val toRemove = items.sortedBy { it.lastAccessed }.take(items.size - historyLimit)
        val remaining = items - toRemove.toSet()
        _items.value = remaining.sortedByDescending { it.lastAccessed }

        toRemove.forEach { entry ->
            val meta = blobs[entry.blobHash] ?: return@forEach
            val remainingRefs = remaining.count { it.blobHash == entry.blobHash }
            if (remainingRefs == 0) {
                blobs.remove(entry.blobHash)
                File(casDir, entry.blobHash).delete()
            } else {
                blobs[entry.blobHash] = meta.copy(refCount = remainingRefs)
            }
        }
    }

    private fun nextId(): Long = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

