package org.joefang.letterbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Service for fetching and caching remote images through DuckDuckGo's privacy proxy.
 * 
 * Features:
 * - Content-addressed storage for cached images
 * - Proper User-Agent and Referer headers to minimize metadata leaks
 * - Automatic cache management with size limits
 */
class ImageCacheService(
    private val context: Context,
    private val maxCacheSizeBytes: Long = 50 * 1024 * 1024 // 50 MB default
) {
    private val cacheDir: File = File(context.cacheDir, "images").also { it.mkdirs() }
    
    companion object {
        private const val DUCKDUCKGO_PROXY_BASE = "https://external-content.duckduckgo.com/iu/"
        
        // Use a popular Chrome User-Agent to minimize metadata leaks
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        
        // Set referrer to DuckDuckGo domain
        private const val REFERER = "https://duckduckgo.com/"
        
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
    }
    
    /**
     * Fetch an image through DuckDuckGo proxy with caching.
     * 
     * @param originalUrl The original image URL to fetch
     * @param useProxy Whether to use DuckDuckGo proxy (if false, fetches directly)
     * @return Cached file containing the image, or null if fetch failed
     */
    suspend fun fetchImage(originalUrl: String, useProxy: Boolean = true): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Calculate content hash of the original URL for cache key
                val cacheKey = sha256(originalUrl)
                val cachedFile = File(cacheDir, cacheKey)
                
                // Return cached file if it exists
                if (cachedFile.exists()) {
                    return@withContext cachedFile
                }
                
                // Construct the fetch URL
                val fetchUrl = if (useProxy) {
                    val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
                    "$DUCKDUCKGO_PROXY_BASE?u=$encodedUrl"
                } else {
                    originalUrl
                }
                
                // Fetch the image
                val imageBytes = fetchImageBytes(fetchUrl) ?: return@withContext null
                
                // Write to cache
                cachedFile.writeBytes(imageBytes)
                
                // Enforce cache size limit
                enforceCacheLimit()
                
                cachedFile
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Fetch image bytes from URL with proper headers.
     */
    private fun fetchImageBytes(urlString: String): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            
            // Set proper headers to minimize metadata leaks
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", REFERER)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            
            return connection.inputStream.readBytes()
        } catch (e: IOException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * Enforce cache size limit by deleting oldest files.
     */
    private fun enforceCacheLimit() {
        val files = cacheDir.listFiles() ?: return
        val totalSize = files.sumOf { it.length() }
        
        if (totalSize <= maxCacheSizeBytes) return
        
        // Sort by last modified time (oldest first)
        val sortedFiles = files.sortedBy { it.lastModified() }
        
        var currentSize = totalSize
        for (file in sortedFiles) {
            if (currentSize <= maxCacheSizeBytes) break
            currentSize -= file.length()
            file.delete()
        }
    }
    
    /**
     * Clear all cached images.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
    
    /**
     * Get current cache size in bytes.
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
