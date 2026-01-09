package org.joefang.letterbox.ffi.proxy

import org.junit.Test
import org.junit.BeforeClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * Host-side tests for the WARP image proxy Rust FFI.
 *
 * These tests verify the proxy API behavior without requiring network access.
 * They test:
 * - Proxy status reporting
 * - Error handling for invalid URLs
 * - Data structure correctness
 *
 * Note: These tests require the native library (letterbox_proxy) to be built and
 * available via LD_LIBRARY_PATH. The CI workflow builds the library before running tests.
 */
class ImageProxyTest {

    companion object {
        /**
         * Load the native library before running tests.
         *
         * The library should be available via LD_LIBRARY_PATH in CI.
         * If this fails, the CI workflow needs to build the library first.
         */
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            uniffiEnsureInitialized()
        }
    }

    @Test
    fun `proxy status before init reports not ready`() {
        val status = proxyStatus()

        assertFalse(status.ready)
        assertFalse(status.warpEnabled)
        assertEquals(0u, status.cacheSize)
        assertNotNull(status.lastError)
    }

    @Test
    fun `proxy status structure has all expected fields`() {
        val status = proxyStatus()

        // Verify we can access all fields without exceptions
        // Using named variables to avoid Kotlin reserved '_' issue
        val ready = status.ready
        val warpEnabled = status.warpEnabled
        val endpoint = status.endpoint
        val lastError = status.lastError
        val cacheSize = status.cacheSize
        
        // Verify types by using them
        assertNotNull(ready.toString())
        assertNotNull(warpEnabled.toString())
        assertNotNull(cacheSize.toString())
        // endpoint and lastError can be null
    }

    @Test
    fun `proxy init with valid path succeeds`() {
        val tempDir = java.io.File.createTempFile("proxy_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            val status = proxyStatus()
            assertTrue(status.ready)
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `proxy clear cache succeeds after init`() {
        val tempDir = java.io.File.createTempFile("proxy_cache_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)
            proxyClearCache()

            val status = proxyStatus()
            assertEquals(0u, status.cacheSize)
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `proxy fetch image with invalid URL throws exception`() {
        val tempDir = java.io.File.createTempFile("proxy_url_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            assertFailsWith<ProxyException.InvalidUrl> {
                proxyFetchImage("not-a-valid-url", null)
            }
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `proxy fetch image with ftp scheme throws exception`() {
        val tempDir = java.io.File.createTempFile("proxy_ftp_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            assertFailsWith<ProxyException.InvalidUrl> {
                proxyFetchImage("ftp://files.example.com/image.png", null)
            }
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `proxy fetch image with file scheme throws exception`() {
        val tempDir = java.io.File.createTempFile("proxy_file_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            assertFailsWith<ProxyException.InvalidUrl> {
                proxyFetchImage("file:///etc/passwd", null)
            }
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `proxy fetch images batch with invalid URL returns error in result`() {
        val tempDir = java.io.File.createTempFile("proxy_batch_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            val results = proxyFetchImagesBatch(
                listOf("invalid-url-1", "invalid-url-2"),
                2u
            )

            assertEquals(2, results.size)
            assertFalse(results[0].success)
            assertFalse(results[1].success)
            assertNotNull(results[0].error)
            assertNotNull(results[1].error)
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    fun `batch image result contains url field`() {
        val tempDir = java.io.File.createTempFile("proxy_batch_url_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            proxyInit(tempDir.absolutePath, 100u)

            val testUrl = "https://example.com/test.png"
            val results = proxyFetchImagesBatch(listOf(testUrl), 1u)

            assertEquals(1, results.size)
            // URL should be preserved in the result (even for failures)
            assertTrue(results[0].url.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
            try {
                proxyShutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }
}
