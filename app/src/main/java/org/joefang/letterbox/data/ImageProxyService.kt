package org.joefang.letterbox.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joefang.letterbox.attempt
import org.joefang.letterbox.describe
import org.joefang.letterbox.ffi.proxy.HttpFetchResponse
import org.joefang.letterbox.ffi.proxy.FetchedResource
import org.joefang.letterbox.ffi.proxy.ProxyStatus
import org.joefang.letterbox.ffi.proxy.TlsSelfTestOutcome
import org.joefang.letterbox.ffi.proxy.TunnelVerification
import org.joefang.letterbox.ffi.proxy.UpdateResult
import org.joefang.letterbox.ffi.proxy.WarpDiagnostics
import org.joefang.letterbox.ffi.proxy.WarpStoredConfig
import org.joefang.letterbox.ffi.proxy.proxyCheckForUpdate
import org.joefang.letterbox.ffi.proxy.proxyClearCache
import org.joefang.letterbox.ffi.proxy.proxyDiagnostics
import org.joefang.letterbox.ffi.proxy.proxyFetchImage
import org.joefang.letterbox.ffi.proxy.proxyFetchImagesBatch
import org.joefang.letterbox.ffi.proxy.proxyFetchSubresource
import org.joefang.letterbox.ffi.proxy.proxyFetchUrl
import org.joefang.letterbox.ffi.proxy.proxyInit
import org.joefang.letterbox.ffi.proxy.proxyResetIdentity
import org.joefang.letterbox.ffi.proxy.proxyShutdown
import org.joefang.letterbox.ffi.proxy.proxyStatus
import org.joefang.letterbox.ffi.proxy.proxyStoredConfig
import org.joefang.letterbox.ffi.proxy.proxyTlsSelfTest
import org.joefang.letterbox.ffi.proxy.proxyVerifyTunnel
import java.io.File

/**
 * Result of fetching one subresource through the proxy.
 *
 * This was `ImageFetchResult`, and the rename is the point rather than tidying:
 * the renderer asks this service for every external thing a message references —
 * pictures, stylesheets, web fonts — and the old name was the visible end of a
 * chain that refused all but the first. A message whose layout lives in a remote
 * stylesheet rendered unstyled, and reported "expected image, got text/css".
 */
sealed class ResourceFetchResult {
    /** Successfully fetched bytes, with the type the server declared. */
    data class Success(
        val mimeType: String,
        val data: ByteArray,
        val fromCache: Boolean,
        val finalUrl: String
    ) : ResourceFetchResult() {
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
    data class Error(val message: String) : ResourceFetchResult()
}

/**
 * Fetches images and other subresources through the privacy-preserving WARP
 * proxy, wrapping the Rust FFI for the letterbox-proxy crate.
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

    /**
     * Whether [proxyInit] has run. `@Volatile` because it is written and read
     * from different `Dispatchers.IO` threads; without it a coroutine can keep
     * missing the update and re-initialise for ever. Correctness does not rest
     * on it — `proxy_init` re-checks under its own lock and is idempotent — so
     * this is an optimisation that must merely be *visible*, not a lock.
     */
    @Volatile
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

        attempt {
            val storageDir = File(context.filesDir, "warp_proxy")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            proxyInit(storageDir.absolutePath, DEFAULT_CACHE_SIZE)
        }.fold(
            onSuccess = {
                initialized = true
                true
            },
            onFailure = {
                android.util.Log.e(TAG, "Failed to initialize proxy: ${it.message}", it)
                false
            }
        )
    }

    /**
     * Get the current proxy status.
     */
    suspend fun getStatus(): ProxyStatus? = withContext(Dispatchers.IO) {
        attempt { proxyStatus() }.getOrElse {
            android.util.Log.e(TAG, "Failed to get proxy status: ${it.message}", it)
            null
        }
    }

    /**
     * Collect full WARP tunnel diagnostics for the debug screen.
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
     * Prove end to end that traffic really leaves through the tunnel.
     *
     * Fetches Cloudflare's trace endpoint *through* the tunnel and reports what
     * the far end saw — whether it counted the request as WARP, and which
     * address it would hand to an image server. Unlike [getDiagnostics], which
     * describes a session that exists, this establishes that the path works.
     */
    suspend fun verifyTunnel(): TunnelVerification = withContext(Dispatchers.IO) {
        if (!initialized) {
            check(initialize()) { "Proxy not initialized" }
        }
        proxyVerifyTunnel()
    }

    /**
     * Probe the provisioning TLS path without mutating any Cloudflare state.
     *
     * Drives the real provisioning HTTP client through a single, state-free
     * request so the certificate verifier is actually exercised. Used by the
     * instrumented regression test that guards against `reqwest` silently
     * falling back to the (uninitialized on Android) platform verifier.
     */
    suspend fun tlsSelfTest(): TlsSelfTestOutcome = withContext(Dispatchers.IO) {
        proxyTlsSelfTest()
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
     * Fetch one subresource a rendered message referenced — image, stylesheet,
     * web font, anything inert.
     *
     * This is what the WebView's interceptor calls, and it imposes no content
     * type of its own: `accept` is forwarded from the renderer's own request, so
     * the far end is told what the page actually wants rather than what this
     * layer assumed it wanted. The proxy still refuses executable content; see
     * `is_active_content` in `letterbox-proxy`.
     *
     * @param url the URL the message referenced
     * @param accept the renderer's `Accept` header for this request
     * @param headers optional custom headers to include
     */
    suspend fun fetchSubresource(
        url: String,
        accept: String = "*/*",
        headers: Map<String, String>? = null
    ): ResourceFetchResult = fetching {
        proxyFetchSubresource(url, accept, headers)
    }

    /**
     * Fetch a single image through the privacy proxy, refusing anything that is
     * not one.
     *
     * Distinct from [fetchSubresource] because the callers differ in kind: this
     * one is for code that wants a picture specifically and has nothing sensible
     * to do with a stylesheet. The renderer is not such a caller, and treating it
     * as one is what broke remote content.
     *
     * @param url The URL of the image to fetch
     * @param headers Optional custom headers to include in the request
     * @return Result containing either the image data or an error
     */
    suspend fun fetchImage(
        url: String,
        headers: Map<String, String>? = null
    ): ResourceFetchResult = fetching {
        proxyFetchImage(url, headers)
    }

    /**
     * Run one FFI fetch, mapping both of its failure modes onto the error case.
     *
     * The two fetch entry points differ only in which FFI call they make; the
     * initialise-first rule and the exception-to-value mapping are the same
     * operation and are written once.
     */
    private suspend inline fun fetching(
        crossinline fetch: () -> FetchedResource
    ): ResourceFetchResult = withContext(Dispatchers.IO) {
        if (!initialized && !initialize()) {
            return@withContext ResourceFetchResult.Error("Proxy not initialized")
        }

        attempt { fetch() }.fold(
            onSuccess = { it.asSuccess() },
            onFailure = { ResourceFetchResult.Error(it.describe("Unexpected error")) }
        )
    }

    /** Project an FFI record onto the sealed result the app renders. */
    private fun FetchedResource.asSuccess() = ResourceFetchResult.Success(
        mimeType = mimeType,
        data = data,
        fromCache = fromCache,
        finalUrl = finalUrl
    )

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
    ): Map<String, ResourceFetchResult> = withContext(Dispatchers.IO) {
        if (!initialized) {
            val initResult = initialize()
            if (!initResult) {
                return@withContext urls.associateWith {
                    ResourceFetchResult.Error("Proxy not initialized")
                }
            }
        }

        attempt {
            proxyFetchImagesBatch(urls, maxConcurrent).associate { result ->
                // Eliminated on the response, not on `success`. Rust builds all
                // three fields together so they cannot disagree, but the FFI
                // cannot say so — re-deriving that here needed four `!!`.
                result.url to (result.response?.asSuccess()
                    ?: ResourceFetchResult.Error(result.error ?: "Unknown error"))
            }
        }.getOrElse { failure ->
            urls.associateWith { ResourceFetchResult.Error(failure.describe("Unexpected error")) }
        }
    }

    /**
     * Clear the in-memory image cache.
     */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        attempt { proxyClearCache() }.fold(
            onSuccess = { true },
            onFailure = {
                android.util.Log.e(TAG, "Failed to clear cache: ${it.message}", it)
                false
            }
        )
    }

    /**
     * Shutdown the proxy service and release resources.
     * 
     * After calling this, the service must be initialized again before use.
     */
    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        attempt { proxyShutdown() }.fold(
            onSuccess = {
                initialized = false
                true
            },
            onFailure = {
                android.util.Log.e(TAG, "Failed to shutdown proxy: ${it.message}", it)
                false
            }
        )
    }
}
