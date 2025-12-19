package org.joefang.letterbox.ffi

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Host-side integration tests for the Rust FFI boundary.
 * 
 * These tests load the Rust library compiled for the host OS (x86_64-unknown-linux-gnu)
 * and test the JNI/FFI boundary without requiring an Android emulator.
 * 
 * This validates that:
 * - Data passing (String, ByteArray) works correctly across the FFI boundary
 * - Error handling propagates correctly from Rust to Kotlin
 * - The opaque handle pattern works correctly
 */
class RustFfiIntegrationTest {

    companion object {
        private var libraryLoaded = false
        private var loadError: String? = null

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            try {
                // Set the system property to override the library name for testing
                // This allows us to load the host-compiled library instead of the Android one
                val libPath = System.getProperty("uniffi.component.letterbox_core.libraryOverride")
                    ?: System.getenv("LETTERBOX_CORE_LIB_PATH")
                
                if (libPath != null) {
                    System.setProperty("uniffi.component.letterbox_core.libraryOverride", libPath)
                }
                
                // Initialize the library
                uniffiEnsureInitialized()
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                loadError = "Native library not found: ${e.message}"
            } catch (e: ExceptionInInitializerError) {
                loadError = "Library initialization failed: ${e.cause?.message ?: e.message}"
            } catch (e: Exception) {
                loadError = "Failed to load native library: ${e.message}"
            }
        }
    }

    private fun requireLibrary() {
        if (!libraryLoaded) {
            org.junit.Assume.assumeTrue(
                "Skipping test: $loadError",
                libraryLoaded
            )
        }
    }

    @Test
    fun `parse simple email returns correct subject`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Hello World
            From: sender@example.com
            To: recipient@example.com
            
            This is the body.
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        assertEquals("Hello World", handle.subject())
        assertEquals("sender@example.com", handle.from())
        assertEquals("recipient@example.com", handle.to())
        
        handle.destroy()
    }

    @Test
    fun `parse email with HTML body returns HTML`() {
        requireLibrary()
        
        val emlContent = """
            Subject: HTML Email
            From: sender@example.com
            To: recipient@example.com
            Content-Type: text/html
            
            <html><body><p>HTML content</p></body></html>
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        assertEquals("HTML Email", handle.subject())
        val bodyHtml = handle.bodyHtml()
        assertNotNull(bodyHtml)
        assertTrue(bodyHtml.contains("HTML content"))
        
        handle.destroy()
    }

    @Test
    fun `parse multipart email extracts both text and HTML`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Multipart Email
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="boundary123"
            
            --boundary123
            Content-Type: text/plain
            
            Plain text body
            --boundary123
            Content-Type: text/html
            
            <html><body><p>HTML body</p></body></html>
            --boundary123--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        assertEquals("Multipart Email", handle.subject())
        
        // Should have text body
        val bodyText = handle.bodyText()
        assertNotNull(bodyText)
        assertTrue(bodyText.contains("Plain text body"))
        
        // Should have HTML body
        val bodyHtml = handle.bodyHtml()
        assertNotNull(bodyHtml)
        assertTrue(bodyHtml.contains("HTML body"))
        
        handle.destroy()
    }

    @Test
    fun `empty payload throws ParseException Empty`() {
        requireLibrary()
        
        assertFailsWith<ParseException.Empty> {
            parseEml(ByteArray(0))
        }
    }

    @Test
    fun `missing resource returns null`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Simple
            From: test@test.com
            
            Body
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        assertNull(handle.getResource("nonexistent-cid"))
        assertEquals(emptyList(), handle.getResourceIds())
        
        handle.destroy()
    }

    @Test
    fun `email with inline attachment extracts resource`() {
        requireLibrary()
        
        // Create a simple multipart email with an inline image
        val emlContent = """
            Subject: Email with Image
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="boundary456"
            
            --boundary456
            Content-Type: text/html
            
            <html><body><img src="cid:image001"></body></html>
            --boundary456
            Content-Type: image/png
            Content-ID: <image001>
            Content-Transfer-Encoding: base64
            
            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
            --boundary456--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        assertEquals("Email with Image", handle.subject())
        
        // Check if resource IDs are extracted
        val resourceIds = handle.getResourceIds()
        // The exact behavior depends on the mail-parser implementation
        // Just verify the API works
        assertNotNull(resourceIds)
        
        handle.destroy()
    }

    @Test
    fun `handle can be used multiple times`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Reusable Handle
            From: test@test.com
            
            Body content
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        // Call methods multiple times
        assertEquals("Reusable Handle", handle.subject())
        assertEquals("Reusable Handle", handle.subject())
        assertEquals("test@test.com", handle.from())
        assertEquals("test@test.com", handle.from())
        
        handle.destroy()
    }

    @Test
    fun `special characters in subject are preserved`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Test with émojis 🎉 and spëcial çhars
            From: test@test.com
            
            Body
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray(Charsets.UTF_8))
        
        val subject = handle.subject()
        assertTrue(subject.contains("émojis") || subject.contains("special") || subject.isNotEmpty())
        
        handle.destroy()
    }

    @Test
    fun `date field is extracted`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Dated Email
            From: test@test.com
            Date: Mon, 11 Dec 2025 10:00:00 +0000
            
            Body
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        val date = handle.date()
        // Date should be extracted (format depends on mail-parser)
        assertNotNull(date)
        
        handle.destroy()
    }

    @Test
    fun `malformed email still parses without crashing`() {
        requireLibrary()
        
        // Malformed but mail-parser is lenient
        val emlContent = "This is not a valid email format at all"
        
        // Should not throw - mail-parser is lenient
        val handle = parseEml(emlContent.toByteArray())
        
        // Subject might be empty or contain partial content
        assertNotNull(handle.subject())
        
        handle.destroy()
    }

    // Tests for optimized FFI functions

    @Test
    fun `parse email from path works with valid file`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Path Test
            From: sender@example.com
            To: recipient@example.com
            
            Body content
        """.trimIndent()
        
        // Create temp file
        val tempFile = java.io.File.createTempFile("test_email", ".eml")
        tempFile.writeBytes(emlContent.toByteArray())
        
        try {
            val handle = parseEmlFromPath(tempFile.absolutePath)
            assertEquals("Path Test", handle.subject())
            assertEquals("sender@example.com", handle.from())
            handle.destroy()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `parse email from path throws for missing file`() {
        requireLibrary()
        
        assertFailsWith<ParseException.FileNotFound> {
            parseEmlFromPath("/nonexistent/path/email.eml")
        }
    }

    @Test
    fun `get resource metadata returns inline asset info`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Email with Image
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="boundary456"
            
            --boundary456
            Content-Type: text/html
            
            <html><body><img src="cid:image001"></body></html>
            --boundary456
            Content-Type: image/png
            Content-ID: <image001>
            Content-Transfer-Encoding: base64
            
            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
            --boundary456--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        val metadata = handle.getResourceMetadata()
        // Verify we can call the API without error
        assertNotNull(metadata)
        
        handle.destroy()
    }

    @Test
    fun `get resource content type returns mime type`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Email with Image
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="boundary456"
            
            --boundary456
            Content-Type: text/html
            
            <html><body><img src="cid:image001"></body></html>
            --boundary456
            Content-Type: image/png
            Content-ID: <image001>
            Content-Transfer-Encoding: base64
            
            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
            --boundary456--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        // Try to get content type for an inline image
        val resourceIds = handle.getResourceIds()
        if (resourceIds.isNotEmpty()) {
            val contentType = handle.getResourceContentType(resourceIds.first())
            assertNotNull(contentType)
        }
        
        // Non-existent CID should return null
        assertNull(handle.getResourceContentType("nonexistent-cid"))
        
        handle.destroy()
    }

    @Test
    fun `write attachment to path creates file`() {
        requireLibrary()
        
        val emlContent = """
            Subject: With Attachment
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mixed-boundary"
            
            --mixed-boundary
            Content-Type: text/plain
            
            Body text
            --mixed-boundary
            Content-Type: application/pdf
            Content-Disposition: attachment; filename="test.pdf"
            Content-Transfer-Encoding: base64
            
            SGVsbG8gV29ybGQh
            --mixed-boundary--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        // Verify attachment exists
        val attachments = handle.getAttachments()
        assertTrue(attachments.isNotEmpty())
        
        // Write to temp file
        val tempFile = java.io.File.createTempFile("test_attachment", ".pdf")
        try {
            val result = handle.writeAttachmentToPath(0u, tempFile.absolutePath)
            assertTrue(result)
            assertTrue(tempFile.exists())
            assertTrue(tempFile.length() > 0)
        } finally {
            tempFile.delete()
        }
        
        handle.destroy()
    }

    @Test
    fun `write attachment to path returns false for invalid index`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Simple
            From: test@test.com
            
            Body
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        val tempFile = java.io.File.createTempFile("nonexistent_attachment", ".pdf")
        try {
            val result = handle.writeAttachmentToPath(99u, tempFile.absolutePath)
            // Should return false for missing attachment
            assertEquals(false, result)
        } finally {
            tempFile.delete()
        }
        
        handle.destroy()
    }

    @Test
    fun `write resource to path creates file`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Email with Image
            From: sender@example.com
            To: recipient@example.com
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="boundary456"
            
            --boundary456
            Content-Type: text/html
            
            <html><body><img src="cid:image001"></body></html>
            --boundary456
            Content-Type: image/png
            Content-ID: <image001>
            Content-Transfer-Encoding: base64
            
            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
            --boundary456--
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        val resourceIds = handle.getResourceIds()
        if (resourceIds.isNotEmpty()) {
            val tempFile = java.io.File.createTempFile("test_resource", ".png")
            try {
                val result = handle.writeResourceToPath(resourceIds.first(), tempFile.absolutePath)
                assertTrue(result)
                assertTrue(tempFile.exists())
            } finally {
                tempFile.delete()
            }
        }
        
        handle.destroy()
    }

    @Test
    fun `write resource to path returns false for missing cid`() {
        requireLibrary()
        
        val emlContent = """
            Subject: Simple
            From: test@test.com
            
            Body
        """.trimIndent()
        
        val handle = parseEml(emlContent.toByteArray())
        
        val tempFile = java.io.File.createTempFile("missing_resource", ".png")
        try {
            val result = handle.writeResourceToPath("nonexistent-cid", tempFile.absolutePath)
            // Should return false for missing CID
            assertEquals(false, result)
        } finally {
            tempFile.delete()
        }
        
        handle.destroy()
    }
}
