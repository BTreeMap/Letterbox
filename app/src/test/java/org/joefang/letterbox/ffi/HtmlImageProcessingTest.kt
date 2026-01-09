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
 * Note: These tests require the native library (letterbox_core) to be built and
 * available via LD_LIBRARY_PATH. The CI workflow builds the library before running tests.
 */
class HtmlImageProcessingTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            // The native library should be available via LD_LIBRARY_PATH in CI
            // If this fails, the CI workflow needs to build the library first
            uniffiEnsureInitialized()
        }
    }

    @Test
    fun `extractRemoteImages finds http images`() {
        val html = """<img src="http://example.com/image.jpg" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertEquals("http://example.com/image.jpg", images[0].url)
    }

    @Test
    fun `extractRemoteImages finds https images`() {
        val html = """<img src="https://example.com/image.png" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertEquals("https://example.com/image.png", images[0].url)
    }

    @Test
    fun `extractRemoteImages ignores cid URLs`() {
        val html = """<img src="cid:image123@example.com" alt="test">"""
        val images = extractRemoteImages(html)
        
        assertEquals(0, images.size)
    }

    @Test
    fun `extractRemoteImages handles multiple images`() {
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
        val html = """<img src="https://tracker.com/pixel.png" width="1" height="1">"""
        val images = extractRemoteImages(html)
        
        assertEquals(1, images.size)
        assertTrue(images[0].isTrackingPixel)
    }

    @Test
    fun `extractRemoteImages returns empty list for no images`() {
        val html = """<p>Hello world</p>"""
        val images = extractRemoteImages(html)
        
        assertEquals(0, images.size)
    }
}
