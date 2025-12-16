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
        repository = InMemoryHistoryRepository(tempDir, historyLimit = 2)
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
    fun `evicts least recently used items when over limit`() {
        val first = repository.ingest("One".toByteArray(), "one", null)
        val second = repository.ingest("Two".toByteArray(), "two", null)
        val third = repository.ingest("Three".toByteArray(), "three", null)

        val items = repository.items.value
        assertEquals(2, items.size)
        assertTrue(items.any { it.displayName == "three" })
        assertTrue(items.any { it.displayName == "two" })
        assertFalse(items.any { it.displayName == "one" })

        assertEquals(null, repository.blobMeta(first.blobHash))
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
}
