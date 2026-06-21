package org.joefang.letterbox.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joefang.letterbox.ffi.proxy.BatchImageResult
import org.joefang.letterbox.ffi.proxy.HttpFetchResponse
import org.joefang.letterbox.ffi.proxy.ImageResponse
import org.joefang.letterbox.ffi.proxy.ProxyException
import org.joefang.letterbox.ffi.proxy.ProxyStatus
import org.joefang.letterbox.ffi.proxy.UpdateResult
import org.joefang.letterbox.ffi.proxy.WarpDiagnostics
import org.joefang.letterbox.ffi.proxy.WarpStoredConfig
import org.joefang.letterbox.ffi.proxy.proxyCheckForUpdate
import org.joefang.letterbox.ffi.proxy.proxyClearCache
import org.joefang.letterbox.ffi.proxy.proxyDiagnostics
import org.joefang.letterbox.ffi.proxy.proxyFetchImage
import org.joefang.letterbox.ffi.proxy.proxyFetchImagesBatch
import org.joefang.letterbox.ffi.proxy.proxyFetchUrl
import org.joefang.letterbox.ffi.proxy.proxyInit
import org.joefang.letterbox.ffi.proxy.proxyResetIdentity
import org.joefang.letterbox.ffi.proxy.proxyShutdown
import org.joefang.letterbox.ffi.proxy.proxyStatus
import org.joefang.letterbox.ffi.proxy.proxyStoredConfig
import java.io.File

/**
 * Result of an image fetch operation.
 */
sealed class ImageFetchResult {
    /** Successfully fetched image */
    data class Success(
        val mimeType: String,
        val data: ByteArray,
        val fromCache: Boolean,
        val finalUrl: String
    ) : ImageFetchResult() {
        /**
         * Convert the image to a data URI for embedding in HTML.
         */
        fun toDataUri(): String {
            val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)
            return "data:$mimeType;base64,$base64Data"
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return mimeType == other.mimeType && 
                   data.contentEquals(other.data) && 
                   fromCache == other.fromCache &&
                   finalUrl == other.finalUrl
        }
        
        override fun hashCode(): Int {
            var result = mimeType.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + fromCache.hashCode()
            result = 31 * result + finalUrl.hashCode()
            return result
        }
    }
    
    /** Failed to fetch image */
    data class Error(val message: String) : ImageFetchResult()
}

/**
 * Service for fetching images through the privacy-preserving WARP proxy.
 * 
 * This service wraps the Rust FFI for the letterbox-proxy crate, providing:
 * - Initialization with storage path
 * - Single and batch image fetching
 * - Status monitoring
 * - Graceful shutdown
 *
 * ## Usage
 *
 * ```kotlin
 * val service = ImageProxyService.getInstance(context)
 * service.initialize()
 * 
 * val result = service.fetchImage("https://example.com/image.png")
 * when (result) {
 *     is ImageFetchResult.Success -> {
 *         // Use result.data or result.toDataUri()
 *     }
 *     is ImageFetchResult.Error -> {
 *         Log.e(TAG, "Failed: ${result.message}")
 *     }
 * }
 * ```
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe and can be called from any thread.
 * Suspend functions should be called from a coroutine context.
 */
class ImageProxyService private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ImageProxyService"
        private const val DEFAULT_CACHE_SIZE = 100u
        private const val MAX_CONCURRENT_FETCHES = 8u
        
        @Volatile
        private var instance: ImageProxyService? = null
        
        /**
         * Get the singleton instance of the ImageProxyService.
         */
        fun getInstance(context: Context): ImageProxyService {
            return instance ?: synchronized(this) {
                instance ?: ImageProxyService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private var initialized = false
    
    /**
     * Initialize the proxy service.
     * 
     * This must be called before any fetch operations. It:
     * - Creates the storage directory if needed
     * - Loads or creates WARP credentials
     * - Sets up the in-memory cache
     *
     * This operation is idempotent - calling it multiple times is safe.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        
        try {
            val storageDir = File(context.filesDir, "warp_proxy")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            
            proxyInit(storageDir.absolutePath, DEFAULT_CACHE_SIZE)
            initialized = true
            true
        } catch (e: ProxyException) {
            android.util.Log.e(TAG, "Failed to initialize proxy: ${e.message}", e)
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Unexpected error initializing proxy: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get the current proxy status.
     */
    suspend fun getStatus(): ProxyStatus? = withContext(Dispatchers.IO) {
        try {
            proxyStatus()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get proxy status: ${e.message}", e)
            null
        }
    }

    /**
     * Collect full WireGuard/WARP diagnostics for the debug screen.
     *
     * This forces the tunnel to provision and perform a handshake if it has not
     * already, so it doubles as a connectivity self-test.
     */
    suspend fun getDiagnostics(): WarpDiagnostics = withContext(Dispatchers.IO) {
        if (!initialized) {
            initialize()
        }
        proxyDiagnostics()
    }

    /**
     * Read the persisted WARP identity and tunnel configuration.
     *
     * Unlike [getDiagnostics], this never provisions or performs a handshake, so
     * it succeeds even when the tunnel is down — the primary tool for inspecting
     * a connection that refuses to come up.
     */
    suspend fun getStoredConfig(): WarpStoredConfig = withContext(Dispatchers.IO) {
        if (!initialized) {
            initialize()
        }
        proxyStoredConfig()
    }

    /**
     * Refresh the WARP identity: regenerate the keypair and re-register with
     * Cloudflare, replacing the persisted configuration.
     *
     * The old device is best-effort deleted and the live tunnel is torn down;
     * the next [getDiagnostics] call rebuilds and verifies the new tunnel.
     */
    suspend fun resetIdentity(): WarpStoredConfig = withContext(Dispatchers.IO) {
        if (!initialized) {
            check(initialize()) { "Proxy not initialized" }
        }
        proxyResetIdentity()
    }

    /**
     * Fetch an arbitrary URL through the WARP tunnel (non-image content allowed).
     *
     * Used by the update checker so the request to GitHub never leaks the real IP.
     */
    suspend fun fetchUrl(
        url: String,
        headers: Map<String, String>? = null
    ): HttpFetchResponse = withContext(Dispatchers.IO) {
        if (!initialized) {
            check(initialize()) { "Proxy not initialized" }
        }
        proxyFetchUrl(url, headers)
    }

    /**
     * Check GitHub releases for a newer version, tunnelled through WARP.
     *
     * @param currentVersion the running version (e.g. "v1.2.3")
     * @param repo optional "owner/name"; defaults to the official distribution repo
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        repo: String? = null
    ): UpdateResult = withContext(Dispatchers.IO) {
        if (!initialized) {
            check(initialize()) { "Proxy not initialized" }
        }
        proxyCheckForUpdate(currentVersion, repo)
    }
    
    /**
     * Fetch a single image through the privacy proxy.
     *
     * @param url The URL of the image to fetch
     * @param headers Optional custom headers to include in the request
     * @return Result containing either the image data or an error
     */
    suspend fun fetchImage(
        url: String,
        headers: Map<String, String>? = null
    ): ImageFetchResult = withContext(Dispatchers.IO) {
        if (!initialized) {
            val initResult = initialize()
            if (!initResult) {
                return@withContext ImageFetchResult.Error("Proxy not initialized")
            }
        }
        
        try {
            val response = proxyFetchImage(url, headers)
            ImageFetchResult.Success(
                mimeType = response.mimeType,
                data = response.data,
                fromCache = response.fromCache,
                finalUrl = response.finalUrl
            )
        } catch (e: ProxyException) {
            ImageFetchResult.Error(e.message ?: "Unknown error")
        } catch (e: Exception) {
            ImageFetchResult.Error(e.message ?: "Unexpected error")
        }
    }
    
    /**
     * Fetch multiple images in parallel through the privacy proxy.
     *
     * @param urls List of image URLs to fetch
     * @param maxConcurrent Maximum number of concurrent fetches (1-32)
     * @return Map of URL to result for each image
     */
    suspend fun fetchImages(
        urls: List<String>,
        maxConcurrent: UInt = MAX_CONCURRENT_FETCHES
    ): Map<String, ImageFetchResult> = withContext(Dispatchers.IO) {
        if (!initialized) {
            val initResult = initialize()
            if (!initResult) {
                return@withContext urls.associateWith { 
                    ImageFetchResult.Error("Proxy not initialized") 
                }
            }
        }
        
        try {
            val results = proxyFetchImagesBatch(urls, maxConcurrent)
            results.associate { result ->
                val fetchResult = if (result.success && result.response != null) {
                    ImageFetchResult.Success(
                        mimeType = result.response!!.mimeType,
                        data = result.response!!.data,
                        fromCache = result.response!!.fromCache,
                        finalUrl = result.response!!.finalUrl
                    )
                } else {
                    ImageFetchResult.Error(result.error ?: "Unknown error")
                }
                result.url to fetchResult
            }
        } catch (e: ProxyException) {
            urls.associateWith { ImageFetchResult.Error(e.message ?: "Unknown error") }
        } catch (e: Exception) {
            urls.associateWith { ImageFetchResult.Error(e.message ?: "Unexpected error") }
        }
    }
    
    /**
     * Clear the in-memory image cache.
     */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            proxyClearCache()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to clear cache: ${e.message}", e)
            false
        }
    }
    
    /**
     * Shutdown the proxy service and release resources.
     * 
     * After calling this, the service must be initialized again before use.
     */
    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        try {
            proxyShutdown()
            initialized = false
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to shutdown proxy: ${e.message}", e)
            false
        }
    }
}
