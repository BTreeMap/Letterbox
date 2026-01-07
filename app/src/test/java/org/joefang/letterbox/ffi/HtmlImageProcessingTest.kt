package org.joefang.letterbox.ffi

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Rust-based HTML image processing functions.
 * 
 * These tests verify the extractRemoteImages FFI function which extracts
 * remote image URLs from HTML content for privacy analysis.
 * 
 * Note: These tests require the native library to be loaded. If the library
 * is not available, tests will be skipped.
 */
class HtmlImageProcessingTest {

    companion object {
        private var libraryLoaded = false
        private var loadError: String? = null

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            try {
                val libPath = System.getProperty("uniffi.component.letterbox_core.libraryOverride")
                    ?: System.getenv("LETTERBOX_CORE_LIB_PATH")

                if (libPath != null) {
                    System.setProperty("uniffi.component.letterbox_core.libraryOverride", libPath)
                }

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
        org.junit.Assume.assumeTrue(
            "Skipping test: $loadError",
            libraryLoaded
        )
    }

    @Test
    fun `extractRemoteImages finds http images`() {
        requireLibrary()

        val html = """<img src="http://example.com/image.jpg" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertEquals("http://example.com/image.jpg", images[0].url)
    }

    @Test
    fun `extractRemoteImages finds https images`() {
        requireLibrary()

        val html = """<img src="https://example.com/image.png" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertEquals("https://example.com/image.png", images[0].url)
    }

    @Test
    fun `extractRemoteImages ignores cid URLs`() {
        requireLibrary()

        val html = """<img src="cid:image123@example.com" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(0, images.size)
    }

    @Test
    fun `extractRemoteImages handles multiple images`() {
        requireLibrary()

        val html = """
            <img src="http://example.com/1.jpg">
            <img src="https://example.com/2.png">
            <img src="cid:inline@example.com">
        """.trimIndent()
        
        val images = extractRemoteImages(html)
        
        assertEquals(2, images.size)
    }

    @Test
    fun `extractRemoteImages detects tracking pixels`() {
        requireLibrary()

        val html = """<img src="https://tracker.com/pixel.png" width="1" height="1">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertTrue(images[0].isTrackingPixel)
    }

    @Test
    fun `extractRemoteImages returns empty list for no images`() {
        requireLibrary()

        val html = """<p>Hello world</p>"""
        val images = extractRemoteImages(html)
        
        assertEquals(0, images.size)
    }
}
