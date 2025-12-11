package com.btreemap.letterbox

import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BlobMeta(
    val hash: String,
    val sizeBytes: Long,
    val refCount: Int
)

data class HistoryEntry(
    val id: Long,
    val blobHash: String,
    val displayName: String,
    val originalUri: String?,
    val lastAccessed: Long
)

class HistoryRepository(
    private val baseDir: File,
    private val historyLimit: Int = 10
) {
    private val casDir: File = File(baseDir, "cas").also { it.mkdirs() }
    private val blobs = Collections.synchronizedMap(mutableMapOf<String, BlobMeta>())
    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

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
