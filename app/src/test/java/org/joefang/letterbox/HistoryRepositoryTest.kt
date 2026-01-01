package org.joefang.letterbox

import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
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
        
        // Small delay to ensure timestamp difference
        Thread.sleep(10)
        
        val second = repository.ingest(bytes, "second", null)
        
        // Second ingest should have updated lastAccessed
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
    // Search, Filter, and Sort Tests
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
    fun `search finds emails by subject`() {
        val meta1 = EmailMetadata(subject = "Important Meeting Tomorrow")
        val meta2 = EmailMetadata(subject = "Weekly Report")
        val meta3 = EmailMetadata(subject = "Meeting Notes")
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val results = repository.search("meeting")
        
        assertEquals(2, results.size)
        assertTrue(results.any { it.subject == "Important Meeting Tomorrow" })
        assertTrue(results.any { it.subject == "Meeting Notes" })
    }
    
    @Test
    fun `search finds emails by sender name`() {
        val meta1 = EmailMetadata(subject = "Email 1", senderName = "John Smith")
        val meta2 = EmailMetadata(subject = "Email 2", senderName = "Jane Doe")
        val meta3 = EmailMetadata(subject = "Email 3", senderName = "John Doe")
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val results = repository.search("john")
        
        assertEquals(2, results.size)
        assertTrue(results.all { it.senderName.contains("John", ignoreCase = true) })
    }
    
    @Test
    fun `search finds emails by sender email`() {
        val meta1 = EmailMetadata(subject = "Email 1", senderEmail = "john@example.com")
        val meta2 = EmailMetadata(subject = "Email 2", senderEmail = "jane@company.org")
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        
        val results = repository.search("example.com")
        
        assertEquals(1, results.size)
        assertEquals("john@example.com", results[0].senderEmail)
    }
    
    @Test
    fun `search returns empty list for no matches`() {
        val meta = EmailMetadata(subject = "Normal Email", senderName = "John")
        repository.ingest("1".toByteArray(), "d1", null, meta)
        
        val results = repository.search("xyz123nonexistent")
        
        assertTrue(results.isEmpty())
    }
    
    @Test
    fun `search finds emails by body preview - full text search`() {
        val meta1 = EmailMetadata(
            subject = "Unrelated Subject",
            bodyPreview = "The quarterly budget meeting was successful"
        )
        val meta2 = EmailMetadata(
            subject = "Another Subject",
            bodyPreview = "Please review the attached document"
        )
        val meta3 = EmailMetadata(
            subject = "Third Subject",
            bodyPreview = "Budget planning for next quarter"
        )
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        // Search for "budget" should find emails 1 and 3 via body preview
        val results = repository.search("budget")
        
        assertEquals(2, results.size)
        assertTrue(results.any { it.bodyPreview.contains("budget", ignoreCase = true) })
    }
    
    @Test
    fun `search with empty query returns all items`() {
        repository.ingest("1".toByteArray(), "d1", null)
        repository.ingest("2".toByteArray(), "d2", null)
        
        val results = repository.search("")
        
        assertEquals(2, results.size)
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
    fun `getSorted by date descending returns newest first`() {
        val meta1 = EmailMetadata(emailDate = 1000L)
        val meta2 = EmailMetadata(emailDate = 3000L)
        val meta3 = EmailMetadata(emailDate = 2000L)
        
        repository.ingest("1".toByteArray(), "oldest", null, meta1)
        repository.ingest("2".toByteArray(), "newest", null, meta2)
        repository.ingest("3".toByteArray(), "middle", null, meta3)
        
        val sorted = repository.getSorted(SortField.DATE, SortDirection.DESCENDING)
        
        assertEquals("newest", sorted[0].displayName)
        assertEquals("middle", sorted[1].displayName)
        assertEquals("oldest", sorted[2].displayName)
    }
    
    @Test
    fun `getSorted by date ascending returns oldest first`() {
        val meta1 = EmailMetadata(emailDate = 1000L)
        val meta2 = EmailMetadata(emailDate = 3000L)
        val meta3 = EmailMetadata(emailDate = 2000L)
        
        repository.ingest("1".toByteArray(), "oldest", null, meta1)
        repository.ingest("2".toByteArray(), "newest", null, meta2)
        repository.ingest("3".toByteArray(), "middle", null, meta3)
        
        val sorted = repository.getSorted(SortField.DATE, SortDirection.ASCENDING)
        
        assertEquals("oldest", sorted[0].displayName)
        assertEquals("middle", sorted[1].displayName)
        assertEquals("newest", sorted[2].displayName)
    }
    
    @Test
    fun `getSorted by subject ascending returns alphabetical order`() {
        val meta1 = EmailMetadata(subject = "Zebra")
        val meta2 = EmailMetadata(subject = "Apple")
        val meta3 = EmailMetadata(subject = "Mango")
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val sorted = repository.getSorted(SortField.SUBJECT, SortDirection.ASCENDING)
        
        assertEquals("Apple", sorted[0].subject)
        assertEquals("Mango", sorted[1].subject)
        assertEquals("Zebra", sorted[2].subject)
    }
    
    @Test
    fun `getSorted by sender uses name when available`() {
        val meta1 = EmailMetadata(senderName = "Zach", senderEmail = "a@x.com")
        val meta2 = EmailMetadata(senderName = "Adam", senderEmail = "z@x.com")
        val meta3 = EmailMetadata(senderName = "", senderEmail = "mike@x.com") // No name
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val sorted = repository.getSorted(SortField.SENDER, SortDirection.ASCENDING)
        
        // Adam, mike@x.com, Zach
        assertEquals("Adam", sorted[0].displaySender)
        assertEquals("mike@x.com", sorted[1].displaySender)
        assertEquals("Zach", sorted[2].displaySender)
    }
    
    @Test
    fun `getWithAttachments filters correctly`() {
        val meta1 = EmailMetadata(subject = "With", hasAttachments = true)
        val meta2 = EmailMetadata(subject = "Without", hasAttachments = false)
        val meta3 = EmailMetadata(subject = "Also With", hasAttachments = true)
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val filtered = repository.getWithAttachments()
        
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.hasAttachments })
    }
    
    @Test
    fun `getByDateRange filters within bounds`() {
        val meta1 = EmailMetadata(emailDate = 1000L)
        val meta2 = EmailMetadata(emailDate = 2000L)
        val meta3 = EmailMetadata(emailDate = 3000L)
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val filtered = repository.getByDateRange(1500L, 2500L)
        
        assertEquals(1, filtered.size)
        assertEquals(2000L, filtered[0].emailDate)
    }
    
    @Test
    fun `getBySender filters by partial match`() {
        val meta1 = EmailMetadata(senderEmail = "john@example.com", senderName = "John")
        val meta2 = EmailMetadata(senderEmail = "jane@company.org", senderName = "Jane")
        val meta3 = EmailMetadata(senderEmail = "johnson@example.com", senderName = "Johnson")
        
        repository.ingest("1".toByteArray(), "d1", null, meta1)
        repository.ingest("2".toByteArray(), "d2", null, meta2)
        repository.ingest("3".toByteArray(), "d3", null, meta3)
        
        val filtered = repository.getBySender("john")
        
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.senderName == "John" })
        assertTrue(filtered.any { it.senderName == "Johnson" })
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
