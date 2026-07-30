package org.joefang.letterbox

import android.util.Log
import org.joefang.letterbox.data.BlobDao
import org.joefang.letterbox.data.BlobEntity
import org.joefang.letterbox.data.HistoryItemDao
import org.joefang.letterbox.data.HistoryItemEntity
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "HistoryRepository"

/**
 * Rows read per page while exporting.
 *
 * Small enough that peak memory does not track the cache size, large enough that
 * a full export is not dominated by query round trips.
 */
private const val EXPORT_PAGE_SIZE = 200

/**
 * This row with `search_text` derived from its own searchable fields.
 *
 * The single write-side application of the folding rule, so the stored haystack
 * and the needle built by [HistoryQuery] can never fold differently. Lives here
 * rather than beside [searchTextOf] to keep that file free of Room types.
 */
private fun HistoryItemEntity.withSearchText(): HistoryItemEntity = copy(
    searchText = searchTextOf(
        subject = subject,
        senderName = senderName,
        senderEmail = senderEmail,
        displayName = displayName,
        bodyPreview = bodyPreview
    )
)

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

    /**
     * Serialises writing a blob file against registering its row, and both
     * against [reclaimOrphanedBlobs].
     *
     * Without it the sweep could observe a freshly written file in the window
     * before its `blobs` row exists and delete content that is about to be read.
     */
    private val blobMutex = Mutex()

    init {
        // Load initial items from database
        scope.launch {
            try {
                historyItemDao.getAllOrderedByAccess().collect { entities ->
                    _items.value = entities.map { it.toHistoryEntry() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading items", e)
            }
        }

        // Reconcile cas/ against the database once, off the UI path.
        scope.launch {
            try {
                val reclaimed = reclaimOrphanedBlobs()
                if (reclaimed > 0) {
                    Log.i(TAG, "Reclaimed $reclaimed bytes of orphaned blobs")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Orphan reclamation failed", e)
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
                repairSearchText(existingItem)
                return@withContext existingItem.copy(lastAccessed = now).toHistoryEntry()
            }
            
            // New content: write the file and register its row as one unit, so a
            // concurrent sweep can never see the file without its row. A crash
            // between the two leaves an orphan the next sweep reclaims.
            blobMutex.withLock {
                if (blobDao.getByHash(hash) == null) {
                    File(casDir, hash).writeBytes(bytes)
                    blobDao.insert(BlobEntity(hash, bytes.size.toLong(), 1))
                }
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
            ).withSearchText()
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
     * Clear all history entries and their blobs.
     *
     * Two `DELETE` statements and one directory sweep, rather than loading every
     * row and issuing one statement per entry. Any file left behind by a partial
     * failure is an orphan the next [reclaimOrphanedBlobs] reclaims.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            blobMutex.withLock {
                historyItemDao.deleteAll()
                blobDao.deleteAll()
                casDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * Entry count and total cached bytes.
     *
     * Two aggregate queries returning one row each. This runs on every history
     * change, so it must not scale with the cache: the previous implementation
     * loaded every row and then called `File.length()` once per blob, which is
     * one filesystem syscall per cached email.
     *
     * Sizes come from `blobs.size_bytes`, recorded from the content length at
     * ingestion, rather than from the file system. The two agree because the
     * file is written with exactly those bytes.
     */
    suspend fun getCacheStats(): CacheStats {
        return withContext(Dispatchers.IO) {
            CacheStats(
                entryCount = historyItemDao.count(),
                totalSizeBytes = blobDao.totalSizeBytes() ?: 0L
            )
        }
    }

    /**
     * Delete `cas/` files the database does not know about, returning the number
     * of bytes reclaimed.
     *
     * The database and the blob directory are two stores that can disagree.
     * `fallbackToDestructiveMigration()` drops both tables on a schema change but
     * cannot touch the file system, so every schema bump strands the entire
     * previous cache on disk — invisible to [getCacheStats], unreachable from
     * "Clear cache", and never reclaimed. A crash between writing a blob and
     * inserting its row leaves the same kind of orphan.
     *
     * Only files with no `blobs` row are removed, so nothing still reachable from
     * a user's history is ever deleted. Holding [blobMutex] excludes a concurrent
     * [ingest] whose file exists but whose row does not yet.
     */
    suspend fun reclaimOrphanedBlobs(): Long {
        return withContext(Dispatchers.IO) {
            blobMutex.withLock {
                reclaimUnknownFiles(casDir, blobDao.allHashes().toHashSet())
            }
        }
    }

    /**
     * Write every cached email into [output] as a zip of `.eml` files.
     *
     * Takes ownership of [output] and closes it.
     *
     * Bounded in memory regardless of how large the cache has grown: rows are
     * read one page at a time and each message is streamed from disk into the
     * archive, so nothing here holds the whole mailbox — or even a whole message.
     * That matters because the cache never evicts.
     *
     * [onProgress] is called with `(processed, total)` after each message. `total`
     * is sampled once at the start; a concurrent ingest would make it a slight
     * underestimate, which is preferable to re-counting per page.
     *
     * Cancellation is honoured between messages. A cancelled export leaves a
     * partial archive at the destination the user chose, which is unavoidable
     * once bytes have been handed to the picker's stream.
     *
     * Needs no [blobMutex]: every row's blob has a `blobs` entry, so
     * [reclaimOrphanedBlobs] can never delete a file this walk will read.
     */
    suspend fun exportAll(
        output: OutputStream,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): ArchiveSummary = withContext(Dispatchers.IO) {
        // Constructed first so that `use` owns the stream from the earliest
        // possible point: a failure while counting must not leak it.
        EmailArchiveWriter(output).use { writer ->
            val total = historyItemDao.count()
            var offset = 0
            while (true) {
                val page = historyItemDao.page(EXPORT_PAGE_SIZE, offset)
                if (page.isEmpty()) break

                for (item in page) {
                    currentCoroutineContext().ensureActive()
                    writer.add(
                        subject = item.subject.ifBlank { item.displayName },
                        source = File(casDir, item.blobHash),
                        timestamp = if (item.emailDate > 0) item.emailDate else item.lastAccessed
                    )
                    onProgress(writer.summary.total, total)
                }
                offset += page.size
            }
            writer.summary
        }
    }

    /**
     * Re-fold a row's `search_text` if the stored value disagrees with what
     * Kotlin's Unicode-aware folding produces.
     *
     * The 3-to-4 migration backfilled the column with SQLite's `lower()`, which
     * folds ASCII only, so a pre-existing "MÜLLER" was stored as "mÜller" and
     * would not match the needle "müller". Re-opening the email repairs it. A
     * no-op — and no write — for every row already correct, which is all of them
     * after the first repair.
     */
    private suspend fun repairSearchText(item: HistoryItemEntity) {
        val folded = item.withSearchText().searchText
        if (folded != item.searchText) {
            historyItemDao.updateSearchText(item.id, folded)
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

