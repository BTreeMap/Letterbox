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
 * Note: These tests require the native library to be loaded. If the library
 * is not available, tests will be skipped.
 */
class ImageProxyTest {

    companion object {
        private var libraryLoaded = false
        private var loadError: String? = null

        /**
         * Load the native library before running tests.
         *
         * The library path can be configured via:
         * - System property: `uniffi.component.letterbox_proxy.libraryOverride`
         * - Environment variable: `LETTERBOX_PROXY_LIB_PATH` (fallback)
         *
         * These are only needed for host-side testing where the library is compiled
         * for the host OS (e.g., x86_64-unknown-linux-gnu) rather than Android.
         * For standard Android instrumented tests, the library is loaded from jniLibs.
         */
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            try {
                // Check for library path override (used for host-side testing)
                val libPath = System.getProperty("uniffi.component.letterbox_proxy.libraryOverride")
                    ?: System.getenv("LETTERBOX_PROXY_LIB_PATH")

                if (libPath != null) {
                    System.setProperty("uniffi.component.letterbox_proxy.libraryOverride", libPath)
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
    fun `proxy status before init reports not ready`() {
        requireLibrary()

        val status = proxyStatus()

        assertFalse(status.ready)
        assertFalse(status.warpEnabled)
        assertEquals(0u, status.cacheSize)
        assertNotNull(status.lastError)
    }

    @Test
    fun `proxy status structure has all expected fields`() {
        requireLibrary()

        val status = proxyStatus()

        // Verify we can access all fields without exceptions
        val _ = status.ready
        val _ = status.warpEnabled
        val _ = status.endpoint
        val _ = status.lastError
        val _ = status.cacheSize
    }

    @Test
    fun `proxy init with valid path succeeds`() {
        requireLibrary()

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
        requireLibrary()

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
        requireLibrary()

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
        requireLibrary()

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
        requireLibrary()

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
        requireLibrary()

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
        requireLibrary()

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
