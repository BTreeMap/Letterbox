package org.joefang.letterbox.ui

import android.net.Uri
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkResolverTest {

    @Test
    fun `http url unchanged and openable`() {
        val result = LinkResolver.resolve("http://example.com/path?q=1")

        assertEquals("http://example.com/path?q=1", result.fixedUrl)
        assertTrue(result.openAllowed)
        assertNotNull(result.openUri)
        assertEquals("http", result.openUri?.scheme)
    }

    @Test
    fun `https url unchanged and openable`() {
        val result = LinkResolver.resolve("https://example.com")

        assertEquals("https://example.com", result.fixedUrl)
        assertTrue(result.openAllowed)
        assertNotNull(result.openUri)
        assertEquals("https", result.openUri?.scheme)
    }

    @Test
    fun `protocol relative url gains https`() {
        val result = LinkResolver.resolve("//example.com/path")

        assertEquals("https://example.com/path", result.fixedUrl)
        assertTrue(result.openAllowed)
        assertEquals(Uri.parse("https://example.com/path"), result.openUri)
    }

    @Test
    fun `schemeless domain gains https`() {
        val result = LinkResolver.resolve("example.com/path")

        assertEquals("https://example.com/path", result.fixedUrl)
        assertTrue(result.openAllowed)
        assertEquals(Uri.parse("https://example.com/path"), result.openUri)
    }

    @Test
    fun `trim whitespace before resolving`() {
        val result = LinkResolver.resolve("  https://example.com/test  ")

        assertEquals("https://example.com/test", result.fixedUrl)
        assertTrue(result.openAllowed)
    }

    @Test
    fun `spaces preserved when openable`() {
        val result = LinkResolver.resolve("example.com/a path")

        assertEquals("https://example.com/a path", result.fixedUrl)
        assertTrue(result.openAllowed)
    }

    @Test
    fun `hard blocked scheme prevents open`() {
        val result = LinkResolver.resolve("javascript:alert(1)")

        assertEquals("javascript:alert(1)", result.fixedUrl)
        assertFalse(result.openAllowed)
        assertNull(result.openUri)
    }

    @Test
    fun `non http scheme prevents open`() {
        val result = LinkResolver.resolve("mailto:test@example.com")

        assertEquals("mailto:test@example.com", result.fixedUrl)
        assertFalse(result.openAllowed)
        assertNull(result.openUri)
    }

    @Test
    fun `relative url not openable`() {
        val result = LinkResolver.resolve("/path/to/resource")

        assertEquals("/path/to/resource", result.fixedUrl)
        assertFalse(result.openAllowed)
    }
}
