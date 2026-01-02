package org.joefang.letterbox

import androidx.sqlite.db.SimpleSQLiteQuery
import org.joefang.letterbox.data.BlobDao
import org.joefang.letterbox.data.BlobEntity
import org.joefang.letterbox.data.EmailFilter
import org.joefang.letterbox.data.HistoryItemDao
import org.joefang.letterbox.data.HistoryItemEntity
import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public data class representing a history entry for the UI layer.
 * 
 * ## Extended Fields for Search/Filter/Sort
 * 
 * This class now includes email metadata extracted during ingestion:
 * - subject: Email subject for display and search
 * - senderEmail/senderName: For sender-based filtering and display
 * - emailDate: For date-based sorting and filtering
 * - hasAttachments: For attachment filter
 */
data class HistoryEntry(
    val id: Long,
    val blobHash: String,
    val displayName: String,
    val originalUri: String?,
    val lastAccessed: Long,
    // Extended fields for search/filter/sort
    val subject: String = "",
    val senderEmail: String = "",
    val senderName: String = "",
    val emailDate: Long = 0,
    val hasAttachments: Boolean = false,
    /** First 500 characters of the email body for full-text search. */
    val bodyPreview: String = ""
) {
    /**
     * Get the display sender - name if available, otherwise email.
     */
    val displaySender: String
        get() = senderName.ifBlank { senderEmail }
    
    /**
     * Get the effective date for sorting/display.
     * Falls back to lastAccessed if emailDate is 0 (unparseable).
     */
    val effectiveDate: Long
        get() = if (emailDate > 0) emailDate else lastAccessed
}

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
 * Email metadata extracted from parsing for ingestion.
 * Used to populate the history item with searchable fields.
 */
data class EmailMetadata(
    val subject: String = "",
    val senderEmail: String = "",
    val senderName: String = "",
    val recipientEmails: String = "",
    val recipientNames: String = "",
    val emailDate: Long = 0,
    val hasAttachments: Boolean = false,
    val bodyPreview: String = ""
)

/**
 * Repository for managing email file history with Content-Addressable Storage (CAS).
 * 
 * ## Features
 * 
 * - **Deduplication**: Same file content is stored only once
 * - **Indefinite caching**: Emails are cached until user explicitly clears them
 * - **Persistence**: Uses Room database for metadata and file system for blobs
 * - **Full-text search**: FTS4 index for fast text search across email content
 * - **Sorting**: By date, subject, or sender
 * - **Filtering**: By date range, sender, attachments
 * 
 * ## Search Implementation
 * 
 * Uses SQLite FTS4 (Full-Text Search) for efficient text matching across:
 * - Subject
 * - Sender email and name
 * - Recipient emails and names
 * - Body preview (first 500 characters)
 * 
 * FTS4 was chosen over FTS5 for better Room compatibility on Android API 26+.
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
    
    // Search, sort, and filter state
    private val _searchQuery = MutableStateFlow("")
    private val _sortField = MutableStateFlow(SortField.DATE)
    private val _sortDirection = MutableStateFlow(SortDirection.DESCENDING)
    private val _filter = MutableStateFlow(EmailFilter())

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
        lastAccessed = lastAccessed,
        subject = subject,
        senderEmail = senderEmail,
        senderName = senderName,
        emailDate = emailDate,
        hasAttachments = hasAttachments,
        bodyPreview = bodyPreview
    )
    
    // =========================================================================
    // Search, Filter, and Sort Methods
    // =========================================================================
    
    /**
     * Search emails using full-text search.
     * 
     * The query is matched against subject, sender, recipients, and body preview.
     * Uses SQLite FTS4 for efficient text matching.
     * 
     * @param query Search query string. Supports FTS4 match syntax.
     * @return Flow of matching history entries
     */
    fun search(query: String): Flow<List<HistoryEntry>> {
        if (query.isBlank()) {
            return items
        }
        // Escape special FTS characters and use prefix matching
        val sanitizedQuery = sanitizeFtsQuery(query)
        return historyItemDao.searchEmailsFlow(sanitizedQuery).map { entities ->
            entities.map { it.toHistoryEntry() }
        }
    }
    
    /**
     * Get items sorted by the specified field and direction.
     */
    fun getSorted(field: SortField, direction: SortDirection): Flow<List<HistoryEntry>> {
        return when (field) {
            SortField.DATE -> when (direction) {
                SortDirection.DESCENDING -> historyItemDao.getAllByDateDesc()
                SortDirection.ASCENDING -> historyItemDao.getAllByDateAsc()
            }
            SortField.SUBJECT -> when (direction) {
                SortDirection.DESCENDING -> historyItemDao.getAllBySubjectDesc()
                SortDirection.ASCENDING -> historyItemDao.getAllBySubjectAsc()
            }
            SortField.SENDER -> when (direction) {
                SortDirection.DESCENDING -> historyItemDao.getAllBySenderDesc()
                SortDirection.ASCENDING -> historyItemDao.getAllBySenderAsc()
            }
        }.map { entities -> entities.map { it.toHistoryEntry() } }
    }
    
    /**
     * Get items filtered by attachment presence.
     */
    fun getWithAttachments(): Flow<List<HistoryEntry>> {
        return historyItemDao.getWithAttachments().map { entities ->
            entities.map { it.toHistoryEntry() }
        }
    }
    
    /**
     * Get items filtered by date range.
     * 
     * @param fromDate Start of range (epoch millis, inclusive)
     * @param toDate End of range (epoch millis, inclusive)
     */
    fun getByDateRange(fromDate: Long, toDate: Long): Flow<List<HistoryEntry>> {
        return historyItemDao.getByDateRange(fromDate, toDate).map { entities ->
            entities.map { it.toHistoryEntry() }
        }
    }
    
    /**
     * Get items filtered by sender (partial match).
     */
    fun getBySender(senderQuery: String): Flow<List<HistoryEntry>> {
        return historyItemDao.getBySender(senderQuery).map { entities ->
            entities.map { it.toHistoryEntry() }
        }
    }
    
    /**
     * Search with combined filters and sorting.
     * 
     * Builds a dynamic SQL query to handle complex filter combinations efficiently.
     * 
     * @param searchQuery Text search query (empty for no text filter)
     * @param filter Filter criteria to apply
     * @param sortField Field to sort by
     * @param sortDirection Sort direction
     * @return Flow of matching and sorted history entries
     */
    fun searchWithFilters(
        searchQuery: String,
        filter: EmailFilter,
        sortField: SortField,
        sortDirection: SortDirection
    ): Flow<List<HistoryEntry>> {
        val queryBuilder = StringBuilder()
        val args = mutableListOf<Any>()
        
        // Start with base query
        if (searchQuery.isNotBlank()) {
            // Use FTS join for text search
            queryBuilder.append("""
                SELECT history_items.* FROM history_items
                JOIN email_fts ON history_items.rowid = email_fts.rowid
                WHERE email_fts MATCH ?
            """.trimIndent())
            args.add(sanitizeFtsQuery(searchQuery))
        } else {
            queryBuilder.append("SELECT * FROM history_items WHERE 1=1")
        }
        
        // Apply filters
        filter.hasAttachments?.let { hasAttach ->
            queryBuilder.append(" AND has_attachments = ?")
            args.add(if (hasAttach) 1 else 0)
        }
        
        filter.dateFrom?.let { from ->
            queryBuilder.append(" AND CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END >= ?")
            args.add(from)
        }
        
        filter.dateTo?.let { to ->
            queryBuilder.append(" AND CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END <= ?")
            args.add(to)
        }
        
        filter.senderContains?.let { sender ->
            queryBuilder.append(" AND (sender_email LIKE ? OR sender_name LIKE ?)")
            val pattern = "%$sender%"
            args.add(pattern)
            args.add(pattern)
        }
        
        // Apply sorting
        val orderClause = when (sortField) {
            SortField.DATE -> "CASE WHEN email_date > 0 THEN email_date ELSE last_accessed END"
            SortField.SUBJECT -> "subject COLLATE NOCASE"
            SortField.SENDER -> "CASE WHEN sender_name != '' THEN sender_name ELSE sender_email END COLLATE NOCASE"
        }
        val directionSql = if (sortDirection == SortDirection.DESCENDING) "DESC" else "ASC"
        queryBuilder.append(" ORDER BY $orderClause $directionSql")
        
        val query = SimpleSQLiteQuery(queryBuilder.toString(), args.toTypedArray())
        return historyItemDao.searchWithFilters(query).map { entities ->
            entities.map { it.toHistoryEntry() }
        }
    }
    
    /**
     * Sanitize a search query for FTS4.
     * Escapes special characters and adds prefix matching.
     */
    private fun sanitizeFtsQuery(query: String): String {
        // FTS4 special characters: " * ( ) - OR AND NOT
        // For simple searches, we escape quotes and use prefix matching
        return query
            .replace("\"", "\"\"")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
    }
}

/**
 * In-memory implementation of HistoryRepository for testing and simple usage.
 * Does not require Room database.
 * 
 * Emails are cached indefinitely until user explicitly clears them.
 * 
 * ## Search Support
 * 
 * This implementation provides simple in-memory search filtering:
 * - Text search across subject, sender, and body preview
 * - Sorting by date, subject, or sender
 * - Filtering by attachments, date range, and sender
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
     * Internal storage for body previews (for search).
     */
    private val bodyPreviews = mutableMapOf<Long, String>()

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
        
        // Store body preview for backward compatibility (deprecated - use entry.bodyPreview)
        bodyPreviews[id] = bodyPreviewText
        
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
        bodyPreviews.remove(entryId)
        
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
        bodyPreviews.clear()
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
    
    // =========================================================================
    // Search, Filter, and Sort Methods
    // =========================================================================
    
    /**
     * Search emails by text query.
     * Searches across subject, sender name/email, and body preview.
     */
    fun search(query: String): List<HistoryEntry> {
        if (query.isBlank()) {
            return _items.value
        }
        val lowerQuery = query.lowercase()
        return _items.value.filter { entry ->
            entry.subject.lowercase().contains(lowerQuery) ||
            entry.senderName.lowercase().contains(lowerQuery) ||
            entry.senderEmail.lowercase().contains(lowerQuery) ||
            entry.bodyPreview.lowercase().contains(lowerQuery)
        }
    }
    
    /**
     * Get sorted items.
     */
    fun getSorted(field: SortField, direction: SortDirection): List<HistoryEntry> {
        val comparator: Comparator<HistoryEntry> = when (field) {
            SortField.DATE -> compareBy { it.effectiveDate }
            SortField.SUBJECT -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.subject }
            SortField.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displaySender }
        }
        val sorted = _items.value.sortedWith(comparator)
        return if (direction == SortDirection.DESCENDING) sorted.reversed() else sorted
    }
    
    /**
     * Get items with attachments.
     */
    fun getWithAttachments(): List<HistoryEntry> {
        return _items.value.filter { it.hasAttachments }
    }
    
    /**
     * Get items within date range.
     */
    fun getByDateRange(fromDate: Long, toDate: Long): List<HistoryEntry> {
        return _items.value.filter { entry ->
            val date = entry.effectiveDate
            date in fromDate..toDate
        }
    }
    
    /**
     * Get items by sender (partial match).
     */
    fun getBySender(senderQuery: String): List<HistoryEntry> {
        val lowerQuery = senderQuery.lowercase()
        return _items.value.filter { entry ->
            entry.senderEmail.lowercase().contains(lowerQuery) ||
            entry.senderName.lowercase().contains(lowerQuery)
        }
    }

    private fun nextId(): Long = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

