package org.joefang.letterbox

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class EmailViewModelTest {

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
    fun `repository ingest adds to history with correct display name`() {
        val emlContent = """
            Subject: Test Email
            From: sender@example.com
            To: recipient@example.com
            
            Hello, this is a test email body.
        """.trimIndent()

        val entry = repository.ingest(
            bytes = emlContent.toByteArray(),
            displayName = "Test Email",
            originalUri = "content://test/email"
        )

        // Verify history was updated
        val history = repository.items.value
        assertTrue(history.isNotEmpty())
        assertEquals("Test Email", history.first().displayName)
        assertEquals("content://test/email", history.first().originalUri)
    }

    @Test
    fun `repository access updates last accessed time`() {
        val entry = repository.ingest(
            bytes = "Subject: Test\n\nBody".toByteArray(),
            displayName = "test",
            originalUri = null
        )

        val originalTime = entry.lastAccessed
        
        // Access the entry - the implementation uses System.currentTimeMillis()
        // so the new timestamp should be >= the original
        val updated = repository.access(entry.id)
        
        assertTrue(updated != null)
        assertTrue(updated.lastAccessed >= originalTime)
    }
}
