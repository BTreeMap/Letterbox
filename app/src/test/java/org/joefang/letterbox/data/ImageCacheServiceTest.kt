package org.joefang.letterbox.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for ImageCacheService testing actual DuckDuckGo API.
 * 
 * These tests make real network requests to verify the proxy works correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImageCacheServiceTest {

    private lateinit var context: Context
    private lateinit var service: ImageCacheService
    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        service = ImageCacheService(context, maxCacheSizeBytes = 10 * 1024 * 1024)
        tempCacheDir = File(context.cacheDir, "images")
        tempCacheDir.mkdirs()
    }

    @After
    fun tearDown() {
        service.clearCache()
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `fetchImage successfully fetches and caches image through DuckDuckGo proxy`() = runBlocking {
        // Use the test image provided by the user
        val testImageUrl = "https://s.joefang.org/Q5s"
        
        // Fetch through DuckDuckGo proxy
        val cachedFile = service.fetchImage(testImageUrl, useProxy = true)
        
        // Verify file was fetched and cached
        assertNotNull(cachedFile, "Image should be fetched successfully")
        assertTrue(cachedFile.exists(), "Cached file should exist")
        assertTrue(cachedFile.length() > 0, "Cached file should have content")
    }

    @Test
    fun `fetchImage uses cache on second request`() = runBlocking {
        val testImageUrl = "https://s.joefang.org/Q5s"
        
        // First fetch
        val firstFetch = service.fetchImage(testImageUrl, useProxy = true)
        assertNotNull(firstFetch)
        val firstModified = firstFetch.lastModified()
        
        // Wait a bit to ensure timestamp would differ if re-downloaded
        Thread.sleep(100)
        
        // Second fetch should use cache
        val secondFetch = service.fetchImage(testImageUrl, useProxy = true)
        assertNotNull(secondFetch)
        assertEquals(firstModified, secondFetch.lastModified(), "Should use cached file")
    }

    @Test
    fun `fetchImage returns null for invalid URL`() = runBlocking {
        val invalidUrl = "https://invalid-url-that-does-not-exist-12345.com/image.jpg"
        
        val result = service.fetchImage(invalidUrl, useProxy = true)
        
        // Should return null for failed fetch
        assertEquals(null, result, "Should return null for invalid URL")
    }

    @Test
    fun `cache size is tracked correctly`() = runBlocking {
        val testImageUrl = "https://s.joefang.org/Q5s"
        
        val initialSize = service.getCacheSize()
        
        // Fetch an image
        service.fetchImage(testImageUrl, useProxy = true)
        
        val afterFetchSize = service.getCacheSize()
        
        assertTrue(afterFetchSize > initialSize, "Cache size should increase after fetch")
    }

    @Test
    fun `clearCache removes all cached files`() = runBlocking {
        val testImageUrl = "https://s.joefang.org/Q5s"
        
        // Fetch an image
        service.fetchImage(testImageUrl, useProxy = true)
        assertTrue(service.getCacheSize() > 0, "Cache should have content")
        
        // Clear cache
        service.clearCache()
        
        assertEquals(0L, service.getCacheSize(), "Cache should be empty after clear")
    }
}
