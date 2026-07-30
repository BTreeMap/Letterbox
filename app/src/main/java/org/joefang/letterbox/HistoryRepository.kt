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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// HistoryEntry, CacheStats and EmailMetadata live in HistoryModels.kt so the
// domain values stay free of Room and coroutine dependencies.

/**
 * Repository for email file history backed by Content-Addressable Storage (CAS).
 *
 * - **Deduplication**: identical content is stored once, keyed by SHA-256
 * - **Indefinite caching**: entries persist until the user clears them
 * - **Persistence**: Room for metadata, the file system for blobs
 *
 * ## Querying
 *
 * This repository exposes the whole history as [items] and does not query,
 * filter or sort. Search, filtering and ordering are one pure function,
 * [HistoryQuery], applied to that list. Keeping the decision out of the
 * repository means it has a single definition and can be tested without a
 * database; see `docs/full-text-search.md`.
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
            try {
                historyItemDao.getAllOrderedByAccess().collect { entities ->
                    _items.value = entities.map { it.toHistoryEntry() }
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryRepository", "Error loading items", e)
            }
        }
    }

    /**
     * Ingest a new email file into the repository.
     * - Computes SHA-256 hash of the content
     * - Stores the file in CAS if not already present
     * - Creates a history entry with email metadata
     * 
     * Emails are cached indefinitely until user explicitly clears them.
     * 
     * ## Deduplication
     * 
     * If an email with the same SHA-256 checksum already exists in history,
     * the existing entry is updated (lastAccessed timestamp) rather than
     * creating a duplicate. This ensures each unique EML file appears only
     * once in the history.
     * 
     * @param bytes Raw email file content
     * @param displayName Display name for the email (usually filename)
     * @param originalUri Source URI for provenance tracking
     * @param metadata Email metadata extracted from parsing for search/filter
     * @return The existing or newly created history entry
     */
    suspend fun ingest(
        bytes: ByteArray, 
        displayName: String, 
        originalUri: String?,
        metadata: EmailMetadata = EmailMetadata()
    ): HistoryEntry {
        return withContext(Dispatchers.IO) {
            val hash = sha256(bytes)
            val now = System.currentTimeMillis()
            
            // Check if history entry already exists for this blob (deduplication)
            val existingItem = historyItemDao.getFirstByBlobHash(hash)
            if (existingItem != null) {
                // Email already in history - update last accessed timestamp and return
                historyItemDao.updateLastAccessed(existingItem.id, now)
                return@withContext existingItem.copy(lastAccessed = now).toHistoryEntry()
            }
            
            // New content - check if blob exists and create if needed
            val blobFile = File(casDir, hash)
            val existingBlob = blobDao.getByHash(hash)
            if (existingBlob == null) {
                blobFile.writeBytes(bytes)
                blobDao.insert(BlobEntity(hash, bytes.size.toLong(), 1))
            }

            // Create new history entry with metadata
            val effectiveDisplayName = displayName.ifBlank { 
                metadata.subject.ifBlank { "Untitled" } 
            }
            
            val entity = HistoryItemEntity(
                blobHash = hash,
                displayName = effectiveDisplayName,
                originalUri = originalUri,
                lastAccessed = now,
                subject = metadata.subject.ifBlank { effectiveDisplayName },
                senderEmail = metadata.senderEmail,
                senderName = metadata.senderName,
                recipientEmails = metadata.recipientEmails,
                recipientNames = metadata.recipientNames,
                emailDate = metadata.emailDate,
                hasAttachments = metadata.hasAttachments,
                bodyPreview = metadata.bodyPreview.take(500)
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
        lastAccessed = lastAccessed,
        subject = subject,
        senderEmail = senderEmail,
        senderName = senderName,
        emailDate = emailDate,
        hasAttachments = hasAttachments,
        bodyPreview = bodyPreview
    )
    
}

/**
 * In-memory stand-in for [HistoryRepository], for tests that need ingestion,
 * deduplication and blob lifecycle without a Room database.
 *
 * Deliberately does **not** reimplement search, filtering or sorting: those
 * belong to [HistoryQuery], which is pure and tested directly. An earlier copy
 * here searched a different set of fields than the code the app actually ran,
 * so the suite could pass while real search misbehaved.
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

    /**
     * Ingest an email file into the repository.
     * 
     * ## Deduplication
     * 
     * If an email with the same SHA-256 checksum already exists in history,
     * the existing entry is updated (lastAccessed timestamp) rather than
     * creating a duplicate. This ensures each unique EML file appears only
     * once in the history.
     */
    @Synchronized
    fun ingest(
        bytes: ByteArray, 
        displayName: String, 
        originalUri: String?,
        metadata: EmailMetadata = EmailMetadata()
    ): HistoryEntry {
        val hash = sha256(bytes)
        val blobFile = File(casDir, hash)
        val now = System.currentTimeMillis()
        
        // Check if blob already exists
        val existingMeta = blobs[hash]
        if (existingMeta == null) {
            blobFile.writeBytes(bytes)
            blobs[hash] = BlobMeta(hash, bytes.size.toLong(), 1)
        }
        
        // Check if history entry already exists for this blob (deduplication)
        val existingEntry = _items.value.find { it.blobHash == hash }
        if (existingEntry != null) {
            // Email already in history - update last accessed timestamp and return
            val updatedEntry = existingEntry.copy(lastAccessed = now)
            _items.value = _items.value.map { 
                if (it.id == existingEntry.id) updatedEntry else it 
            }.sortedByDescending { it.lastAccessed }
            return updatedEntry
        }

        val effectiveDisplayName = displayName.ifBlank { 
            metadata.subject.ifBlank { "Untitled" } 
        }
        
        val id = nextId()
        val bodyPreviewText = metadata.bodyPreview.take(500)
        val newEntry = HistoryEntry(
            id = id,
            blobHash = hash,
            displayName = effectiveDisplayName,
            originalUri = originalUri,
            lastAccessed = now,
            subject = metadata.subject.ifBlank { effectiveDisplayName },
            senderEmail = metadata.senderEmail,
            senderName = metadata.senderName,
            emailDate = metadata.emailDate,
            hasAttachments = metadata.hasAttachments,
            bodyPreview = bodyPreviewText
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

