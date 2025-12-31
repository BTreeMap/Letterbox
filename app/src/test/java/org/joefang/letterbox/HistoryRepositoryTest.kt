package org.joefang.letterbox

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HistoryRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: InMemoryHistoryRepository

    @Before
    fun setUp() {
        tempDir = createTempDirectory(prefix = "letterbox-test").toFile()
        repository = InMemoryHistoryRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `deduplicates blobs and maintains ref counts`() {
        val bytes = "Subject: hello".toByteArray()
        val first = repository.ingest(bytes, "first", null)
        val second = repository.ingest(bytes, "second", null)

        val blobMeta = repository.blobMeta(first.blobHash)
        assertNotNull(blobMeta)
        assertEquals(2, blobMeta.refCount)
        assertEquals(first.blobHash, second.blobHash)
        assertTrue(repository.blobFor(first.blobHash)?.exists() == true)
    }

    @Test
    fun `caches items indefinitely without eviction`() {
        // With no limit, all items should be retained
        val first = repository.ingest("One".toByteArray(), "one", null)
        val second = repository.ingest("Two".toByteArray(), "two", null)
        val third = repository.ingest("Three".toByteArray(), "three", null)

        val items = repository.items.value
        assertEquals(3, items.size)
        assertTrue(items.any { it.displayName == "one" })
        assertTrue(items.any { it.displayName == "two" })
        assertTrue(items.any { it.displayName == "three" })

        // All blobs should still exist
        assertNotNull(repository.blobMeta(first.blobHash))
        assertNotNull(repository.blobMeta(second.blobHash))
        assertNotNull(repository.blobMeta(third.blobHash))
    }

    @Test
    fun `delete removes single entry and cleans up orphan blob`() {
        val entry = repository.ingest("Test content".toByteArray(), "test", null)
        val blobHash = entry.blobHash
        
        // Verify entry exists
        assertEquals(1, repository.items.value.size)
        assertNotNull(repository.blobMeta(blobHash))
        assertTrue(repository.blobFor(blobHash)?.exists() == true)
        
        // Delete the entry
        repository.delete(entry.id)
        
        // Verify entry is removed
        assertEquals(0, repository.items.value.size)
        assertNull(repository.blobMeta(blobHash))
        assertFalse(repository.blobFor(blobHash)?.exists() == true)
    }

    @Test
    fun `delete preserves blob when other entries reference it`() {
        val bytes = "Shared content".toByteArray()
        val first = repository.ingest(bytes, "first", null)
        val second = repository.ingest(bytes, "second", null)
        
        val blobHash = first.blobHash
        assertEquals(second.blobHash, blobHash)
        
        // Delete first entry
        repository.delete(first.id)
        
        // Blob should still exist for second entry
        assertEquals(1, repository.items.value.size)
        assertNotNull(repository.blobMeta(blobHash))
        assertTrue(repository.blobFor(blobHash)?.exists() == true)
    }

    @Test
    fun `clearAll removes all entries and blobs`() {
        repository.ingest("One".toByteArray(), "one", null)
        repository.ingest("Two".toByteArray(), "two", null)
        
        assertEquals(2, repository.items.value.size)
        
        repository.clearAll()
        
        assertEquals(0, repository.items.value.size)
    }

    @Test
    fun `getCacheStats returns correct entry count and size`() {
        // Empty repository
        var stats = repository.getCacheStats()
        assertEquals(0, stats.entryCount)
        assertEquals(0L, stats.totalSizeBytes)

        // Add some entries
        val bytes1 = "First email content".toByteArray()
        val bytes2 = "Second email content that is longer".toByteArray()
        repository.ingest(bytes1, "first", null)
        repository.ingest(bytes2, "second", null)

        stats = repository.getCacheStats()
        assertEquals(2, stats.entryCount)
        assertEquals(bytes1.size.toLong() + bytes2.size.toLong(), stats.totalSizeBytes)
    }

    @Test
    fun `getCacheStats counts deduplicated blobs only once`() {
        val bytes = "Shared email content".toByteArray()
        repository.ingest(bytes, "first", null)
        repository.ingest(bytes, "second", null)  // Same content, should deduplicate

        val stats = repository.getCacheStats()
        assertEquals(2, stats.entryCount)  // Two entries
        assertEquals(bytes.size.toLong(), stats.totalSizeBytes)  // But only one blob size
    }
}
