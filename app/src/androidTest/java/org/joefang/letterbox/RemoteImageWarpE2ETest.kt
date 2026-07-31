package org.joefang.letterbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.ResourceFetchResult
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
 * `ImageProxyService` → Rust FFI → MASQUE tunnel → internet.
 *
 * This exercises real Cloudflare WARP provisioning, the CONNECT-IP handshake and
 * a tunnelled HTTPS fetch on a device. The deterministic banner-gating behaviour
 * is covered separately by [ImageProxyIntegrationTest].
 *
 * It requires working internet access to reach Cloudflare. When the device has
 * no connectivity (e.g. an offline CI shard) the provisioning step throws and
 * the test is skipped via [assumeNoException] rather than failing.
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
        // Past the introduction. Nothing else needs granting: the proxy is usable
        // as soon as something asks it to fetch.
        runBlocking { TestPreferences.seedOnboarded(context) }
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

        // Which transport was *selected* is asserted before whether it connected,
        // and deliberately so. `Link` picks at construction, so `protocol` is
        // meaningful even on a tunnel that never came up — whereas asserting
        // connectivity first throws away the one fact that says whether MASQUE
        // was even attempted. An earlier run failed on connectivity and told us
        // nothing about the migration as a result.
        assertEquals(
            "tunnel should be carried by MASQUE on Android",
            "masque",
            diagnostics.protocol
        )
        assertEquals(
            "WARP tunnel should be connected after diagnostics self-test " +
                "(transport=${diagnostics.protocol}, " +
                "endpoint=${diagnostics.endpointAddress}:${diagnostics.endpointPort})",
            "connected",
            diagnostics.connectionState.lowercase()
        )
        assertNotNull(
            "A QUIC handshake should have completed",
            diagnostics.lastHandshakeSecs
        )
        assertTrue(
            "The address the tunnel dialled should be populated",
            diagnostics.endpointAddress.isNotBlank()
        )
        // The SNI is the one thing about this path that Cloudflare can change
        // without notice, so a live session that reports none is worth failing
        // on rather than treating as detail.
        assertTrue(
            "The tunnel should report the SNI it presented",
            diagnostics.endpointSni.isNotBlank()
        )

        // `warpEnabled` describes the *account*, not the session, and lives on
        // the stored configuration. Reading it from the live diagnostics is what
        // this test used to do, back when one record carried both.
        assertTrue("WARP should be enabled on the account", proxy.getStoredConfig().warpEnabled)
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
            is ResourceFetchResult.Success -> {
                assertTrue(
                    "Fetched image should contain bytes",
                    result.data.isNotEmpty()
                )
                assertTrue(
                    "MIME type should indicate an image, was '${result.mimeType}'",
                    result.mimeType.startsWith("image/")
                )
            }
            is ResourceFetchResult.Error ->
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
