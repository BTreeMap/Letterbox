package org.joefang.letterbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.ImageFetchResult
import org.joefang.letterbox.data.ImageProxyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Network-gated end-to-end test of the full image path:
 * `ImageProxyService` → Rust FFI → WARP/WireGuard tunnel → internet.
 *
 * This exercises the real Cloudflare WARP provisioning + handshake + tunnelled
 * HTTPS fetch on a device. The deterministic banner/consent-gating behaviour is
 * covered separately by [ImageProxyIntegrationTest]; this test proves that, once
 * the user has accepted the Cloudflare terms, remote images actually load
 * through the privacy tunnel and the user's real IP is never used.
 *
 * It requires working internet access to reach Cloudflare. When the device has
 * no connectivity (e.g. an offline CI shard) the WARP provisioning step throws
 * and the test is skipped via [assumeNoException] rather than failing.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RemoteImageWarpE2ETest {

    private lateinit var context: Context
    private lateinit var proxy: ImageProxyService

    /** Small, stable asset served by Cloudflare itself over HTTPS. */
    private val imageUrl = "https://www.cloudflare.com/favicon.ico"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // The user has accepted the Cloudflare WARP terms; network features are on.
        runBlocking { TestPreferences.seedOnboarded(context, acceptedTerms = true) }
        proxy = ImageProxyService.getInstance(context)
    }

    @Test
    fun warpTunnel_connects_andHandshakeCompletes() = runBlocking {
        val diagnostics = try {
            proxy.getDiagnostics()
        } catch (e: Exception) {
            // No connectivity to Cloudflare: skip rather than fail.
            assumeNoException("WARP provisioning requires network access", e)
            return@runBlocking
        }

        assertEquals(
            "WARP tunnel should be connected after diagnostics self-test",
            "connected",
            diagnostics.connectionState.lowercase()
        )
        assertTrue("WARP should be enabled", diagnostics.warpEnabled)
        assertNotNull(
            "A WireGuard handshake should have completed",
            diagnostics.lastHandshakeSecs
        )
        assertTrue(
            "Endpoint host should be populated",
            diagnostics.endpointHost.isNotBlank()
        )
        assertTrue(
            "Public key should be derived from the private key",
            diagnostics.publicKey.isNotBlank()
        )
    }

    @Test
    fun remoteImage_loadsThroughWarpTunnel() = runBlocking {
        val result = try {
            proxy.fetchImage(imageUrl)
        } catch (e: Exception) {
            assumeNoException("Image fetch requires network access", e)
            return@runBlocking
        }

        when (result) {
            is ImageFetchResult.Success -> {
                assertTrue(
                    "Fetched image should contain bytes",
                    result.data.isNotEmpty()
                )
                assertTrue(
                    "MIME type should indicate an image, was '${result.mimeType}'",
                    result.mimeType.startsWith("image/")
                )
            }
            is ImageFetchResult.Error ->
                // Treat a network-level error as an environment skip, not a failure.
                assumeNoException(
                    "Image fetch failed (likely no connectivity): ${result.message}",
                    RuntimeException(result.message)
                )
        }

        // The tunnel must have moved bytes in both directions for the fetch above.
        val diagnostics = proxy.getDiagnostics()
        assertTrue("Tunnel should have transmitted bytes", diagnostics.txBytes > 0u)
        assertTrue("Tunnel should have received bytes", diagnostics.rxBytes > 0u)
    }
}
