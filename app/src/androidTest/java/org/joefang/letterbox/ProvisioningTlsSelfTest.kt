package org.joefang.letterbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.ImageProxyService
import org.joefang.letterbox.ffi.proxy.TlsSelfTestOutcome
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the Android TLS certificate-verifier panic.
 *
 * `reqwest`'s `rustls` feature defaults to `rustls-platform-verifier`, which on
 * Android panics ("Expect rustls-platform-verifier to be initialized") on the
 * first TLS handshake unless the app first wires up a JNI handle to the system
 * trust manager — wiring this project deliberately avoids by handing reqwest a
 * preconfigured `webpki-roots` config instead. That fault only reproduces during
 * a real handshake on a real Dalvik/ART VM, which is exactly what this
 * instrumented test provides.
 *
 * The probe ([ImageProxyService.tlsSelfTest]) issues a single, state-free
 * request against the WARP API host. It registers no device and mutates no
 * Cloudflare state, so it is safe to run on every CI build.
 *
 * Outcomes:
 * - [TlsSelfTestOutcome.Verified] — handshake completed and the certificate
 *   verified against the bundled trust anchors. The strongest pass.
 * - [TlsSelfTestOutcome.Inconclusive] — a transient transport failure (offline
 *   CI shard, DNS, timeout). The verifier was not the cause, so the test passes
 *   but the reason is logged for visibility.
 * - [TlsSelfTestOutcome.PlatformVerifierUninitialized] — the platform verifier
 *   was reached. This is the regression, and it always fails the build.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProvisioningTlsSelfTest {

    @Test
    fun provisioningTls_doesNotUsePlatformVerifier() = runBlocking {
        val proxy = ImageProxyService.getInstance(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        when (val outcome = proxy.tlsSelfTest()) {
            is TlsSelfTestOutcome.Verified -> {
                // Strongest signal: real handshake verified against webpki-roots.
            }

            is TlsSelfTestOutcome.Inconclusive -> {
                // No connectivity to Cloudflare. The verifier was never reached,
                // so this is not a regression — record why and pass.
                println(
                    "TLS self-test inconclusive (non-blocking): ${outcome.reason}"
                )
            }

            is TlsSelfTestOutcome.PlatformVerifierUninitialized -> {
                fail(
                    "WARP provisioning fell back to rustls-platform-verifier, " +
                        "which is uninitialized on Android and panics on the " +
                        "first handshake. The provisioning client must use the " +
                        "preconfigured webpki-roots TLS config. Reason: " +
                        outcome.reason
                )
            }
        }
    }
}
