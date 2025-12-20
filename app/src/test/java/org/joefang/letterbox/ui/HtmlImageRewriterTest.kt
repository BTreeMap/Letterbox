package org.joefang.letterbox.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class HtmlImageRewriterTest {

    @Test
    fun `rewriteImageUrls proxies http images when enabled`() {
        val html = """<img src="http://example.com/image.jpg" alt="test">"""
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = true)
        
        assertTrue(result.contains("https://external-content.duckduckgo.com/iu/?u="))
        assertTrue(result.contains("http%3A%2F%2Fexample.com%2Fimage.jpg"))
    }

    @Test
    fun `rewriteImageUrls proxies https images when enabled`() {
        val html = """<img src="https://example.com/image.png" alt="test">"""
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = true)
        
        assertTrue(result.contains("https://external-content.duckduckgo.com/iu/?u="))
        assertTrue(result.contains("https%3A%2F%2Fexample.com%2Fimage.png"))
    }

    @Test
    fun `rewriteImageUrls does not proxy when disabled`() {
        val html = """<img src="https://example.com/image.jpg" alt="test">"""
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = false)
        
        assertEquals(html, result)
    }

    @Test
    fun `rewriteImageUrls does not proxy cid URLs`() {
        val html = """<img src="cid:image123@example.com" alt="test">"""
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = true)
        
        assertEquals(html, result)
        assertFalse(result.contains("duckduckgo"))
    }

    @Test
    fun `rewriteImageUrls handles multiple images`() {
        val html = """
            <img src="http://example.com/1.jpg">
            <img src="https://example.com/2.png">
            <img src="cid:inline@example.com">
        """.trimIndent()
        
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = true)
        
        // Should proxy the first two
        assertTrue(result.contains("external-content.duckduckgo.com") )
        // Should not proxy the cid: URL
        assertTrue(result.contains("cid:inline@example.com"))
    }

    @Test
    fun `rewriteImageUrls encodes URLs with special characters`() {
        val html = """<img src="https://example.com/image.jpg?w=100&h=200" alt="test">"""
        val result = HtmlImageRewriter.rewriteImageUrls(html, useProxy = true)
        
        // The URL should be encoded including the query parameters
        assertTrue(result.contains("w%3D100%26h%3D200"))
    }

    @Test
    fun `containsRemoteImages detects http images`() {
        val html = """<img src="http://example.com/image.jpg">"""
        assertTrue(HtmlImageRewriter.containsRemoteImages(html))
    }

    @Test
    fun `containsRemoteImages detects https images`() {
        val html = """<img src="https://example.com/image.png">"""
        assertTrue(HtmlImageRewriter.containsRemoteImages(html))
    }

    @Test
    fun `containsRemoteImages returns false for cid URLs`() {
        val html = """<img src="cid:image@example.com">"""
        assertFalse(HtmlImageRewriter.containsRemoteImages(html))
    }

    @Test
    fun `containsRemoteImages returns false for no images`() {
        val html = """<p>Hello world</p>"""
        assertFalse(HtmlImageRewriter.containsRemoteImages(html))
    }

    @Test
    fun `containsRemoteImages is case insensitive`() {
        val html = """<IMG SRC="HTTP://EXAMPLE.COM/IMAGE.JPG">"""
        assertTrue(HtmlImageRewriter.containsRemoteImages(html))
    }
}
