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
    fun `deduplicates emails with same content - returns existing entry`() {
        val bytes = "Subject: hello".toByteArray()
        val first = repository.ingest(bytes, "first", null)
        val second = repository.ingest(bytes, "second", null)

        // Same content should return the same entry (same ID)
        assertEquals(first.id, second.id)
        assertEquals(first.blobHash, second.blobHash)
        
        // Only one entry should exist
        assertEquals(1, repository.items.value.size)
        
        // Blob should still exist
        val blobMeta = repository.blobMeta(first.blobHash)
        assertNotNull(blobMeta)
        assertEquals(1, blobMeta.refCount) // Only one reference, not incremented
        assertTrue(repository.blobFor(first.blobHash)?.exists() == true)
    }
    
    @Test
    fun `deduplication updates lastAccessed timestamp`() {
        val bytes = "Subject: hello".toByteArray()
        val first = repository.ingest(bytes, "first", null)
        val firstAccessed = first.lastAccessed
        
        // Ingest same content again - should return existing entry with updated timestamp
        val second = repository.ingest(bytes, "second", null)
        
        // Second ingest should return the same entry (same ID) with same or later timestamp
        assertEquals(first.id, second.id)
        assertTrue(second.lastAccessed >= firstAccessed)
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
    fun `delete removes entry and blob when only reference`() {
        val bytes = "Unique content".toByteArray()
        val entry = repository.ingest(bytes, "entry", null)
        
        val blobHash = entry.blobHash
        
        // Entry and blob exist
        assertEquals(1, repository.items.value.size)
        assertNotNull(repository.blobMeta(blobHash))
        assertTrue(repository.blobFor(blobHash)?.exists() == true)
        
        // Delete the entry
        repository.delete(entry.id)
        
        // Both entry and blob should be gone
        assertEquals(0, repository.items.value.size)
        assertNull(repository.blobMeta(blobHash))
        assertFalse(repository.blobFor(blobHash)?.exists() == true)
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
    fun `getCacheStats with full deduplication`() {
        val bytes = "Shared email content".toByteArray()
        repository.ingest(bytes, "first", null)
        repository.ingest(bytes, "second", null)  // Same content, should deduplicate

        val stats = repository.getCacheStats()
        // With proper deduplication, same content = one entry only
        assertEquals(1, stats.entryCount)
        assertEquals(bytes.size.toLong(), stats.totalSizeBytes)
    }
    
    // =========================================================================
    // Ingestion of search metadata
    //
    // Search, filter and sort semantics are HistoryQuery's, tested directly in
    // HistoryQueryTest. What matters here is that ingestion persists the fields
    // those queries read.
    // =========================================================================
    
    @Test
    fun `ingest stores email metadata for search`() {
        val metadata = EmailMetadata(
            subject = "Test Subject",
            senderEmail = "sender@example.com",
            senderName = "John Doe",
            recipientEmails = "recipient@example.com",
            recipientNames = "Jane Doe",
            emailDate = 1700000000000L,
            hasAttachments = true,
            bodyPreview = "This is the body preview text"
        )
        
        val entry = repository.ingest("test".toByteArray(), "display", null, metadata)
        
        assertEquals("Test Subject", entry.subject)
        assertEquals("sender@example.com", entry.senderEmail)
        assertEquals("John Doe", entry.senderName)
        assertEquals(1700000000000L, entry.emailDate)
        assertTrue(entry.hasAttachments)
    }
    
    
    
    
    
    
    
    @Test
    fun `body preview is included in HistoryEntry`() {
        val bodyText = "This is the body preview text that should be searchable"
        val metadata = EmailMetadata(
            subject = "Test",
            bodyPreview = bodyText
        )
        
        val entry = repository.ingest("test".toByteArray(), "display", null, metadata)
        
        assertEquals(bodyText, entry.bodyPreview)
    }
    
    
    
    
    
    
    
    
    @Test
    fun `effectiveDate falls back to lastAccessed when emailDate is zero`() {
        val meta = EmailMetadata(emailDate = 0) // Unparseable date
        
        val entry = repository.ingest("1".toByteArray(), "d1", null, meta)
        
        // effectiveDate should be the same as lastAccessed when emailDate is 0
        assertEquals(entry.lastAccessed, entry.effectiveDate)
    }
    
    @Test
    fun `displaySender returns name when available otherwise email`() {
        val meta1 = EmailMetadata(senderName = "John Doe", senderEmail = "john@x.com")
        val meta2 = EmailMetadata(senderName = "", senderEmail = "anonymous@x.com")
        
        val entry1 = repository.ingest("1".toByteArray(), "d1", null, meta1)
        val entry2 = repository.ingest("2".toByteArray(), "d2", null, meta2)
        
        assertEquals("John Doe", entry1.displaySender)
        assertEquals("anonymous@x.com", entry2.displaySender)
    }
}
