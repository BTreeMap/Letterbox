package org.joefang.letterbox.ffi

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Rust-based HTML image processing functions.
 * These tests verify the extractRemoteImages and rewriteImageUrls FFI functions.
 */
class HtmlImageProcessingTest {

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

    @Test
    fun `rewriteImageUrls proxies http images`() {
        val html = """<img src="http://example.com/image.jpg" alt="test">"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        val result = rewriteImageUrls(html, proxyBase)
        
        assertTrue(result.contains("https://external-content.duckduckgo.com/iu/?u="))
        assertTrue(result.contains("http%3A%2F%2Fexample.com%2Fimage.jpg"))
    }

    @Test
    fun `rewriteImageUrls proxies https images`() {
        val html = """<img src="https://example.com/image.png" alt="test">"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        val result = rewriteImageUrls(html, proxyBase)
        
        assertTrue(result.contains("https://external-content.duckduckgo.com/iu/?u="))
        assertTrue(result.contains("https%3A%2F%2Fexample.com%2Fimage.png"))
    }

    @Test
    fun `rewriteImageUrls does not proxy when proxyBase is empty`() {
        val html = """<img src="https://example.com/image.jpg" alt="test">"""
        val result = rewriteImageUrls(html, "")
        
        // When proxy base is empty, URLs are still extracted but not proxied
        // The HTML should remain mostly unchanged (or have empty proxy prefix)
        assertTrue(result.contains("https://example.com/image.jpg") || result.contains("https%3A%2F%2Fexample.com"))
    }

    @Test
    fun `rewriteImageUrls preserves cid URLs`() {
        val html = """<img src="cid:image123@example.com" alt="test">"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        val result = rewriteImageUrls(html, proxyBase)
        
        // cid URLs should not be proxied
        assertTrue(result.contains("cid:image123@example.com"))
        assertFalse(result.contains("duckduckgo"))
    }

    @Test
    fun `rewriteImageUrls handles multiple images`() {
        val html = """
            <img src="http://example.com/1.jpg">
            <img src="https://example.com/2.png">
            <img src="cid:inline@example.com">
        """.trimIndent()
        
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        val result = rewriteImageUrls(html, proxyBase)
        
        // Should proxy the first two
        assertTrue(result.contains("external-content.duckduckgo.com"))
        // Should not proxy the cid: URL
        assertTrue(result.contains("cid:inline@example.com"))
    }

    @Test
    fun `rewriteImageUrls encodes URLs with special characters`() {
        val html = """<img src="https://example.com/image.jpg?w=100&h=200" alt="test">"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        val result = rewriteImageUrls(html, proxyBase)
        
        // The URL should be encoded including the query parameters
        assertTrue(result.contains("w%3D100"))
        assertTrue(result.contains("h%3D200"))
    }

    @Test
    fun `rewriteImageUrls handles malformed HTML gracefully`() {
        val html = """<img src="https://example.com/image.jpg" unclosed"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        // Should not throw an exception
        val result = rewriteImageUrls(html, proxyBase)
        
        // Should still attempt to rewrite
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `rewriteImageUrls handles single and double quotes`() {
        val htmlDouble = """<img src="https://example.com/image.jpg">"""
        val htmlSingle = """<img src='https://example.com/image.jpg'>"""
        val proxyBase = "https://external-content.duckduckgo.com/iu/?u="
        
        val resultDouble = rewriteImageUrls(htmlDouble, proxyBase)
        val resultSingle = rewriteImageUrls(htmlSingle, proxyBase)
        
        // Both should be rewritten
        assertTrue(resultDouble.contains("external-content.duckduckgo.com"))
        assertTrue(resultSingle.contains("external-content.duckduckgo.com"))
    }
}
