package org.joefang.letterbox.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The detail screen's remote-image decision, tested as an algebra rather than
 * through the UI.
 *
 * The regression these guard against: the "Show images" banner and the WebView's
 * network gate each computed their own predicate, and a second condition added
 * to the gate — but not to the banner — made every remote image fail silently.
 * The banner still disappeared on tap, so the app reported success while loading
 * nothing.
 */
class RemoteImagePolicyTest {

    @Test
    fun `no remote images means nothing to offer and nothing to load`() {
        val policy = RemoteImagePolicy.of(hasRemoteImages = false, sessionLoadImages = false)

        assertEquals(RemoteImagePolicy.None, policy)
        assertFalse(policy.showsBanner)
        assertFalse(policy.allowsNetworkLoads)
    }

    @Test
    fun `remote images not yet requested offers the banner and blocks loads`() {
        val policy = RemoteImagePolicy.of(hasRemoteImages = true, sessionLoadImages = false)

        assertEquals(RemoteImagePolicy.Blocked, policy)
        assertTrue(policy.showsBanner)
        assertFalse(policy.allowsNetworkLoads)
    }

    @Test
    fun `asking for images permits loads and retires the banner`() {
        val policy = RemoteImagePolicy.of(hasRemoteImages = true, sessionLoadImages = true)

        assertEquals(RemoteImagePolicy.Allowed, policy)
        assertFalse(policy.showsBanner)
        assertTrue(policy.allowsNetworkLoads)
    }

    @Test
    fun `a standing preference permits loads even when nothing was detected`() {
        // `hasRemoteImages` is a heuristic over the parsed body. If it misses a
        // reference, a user who opted in should still get the image rather than a
        // blocked request with no banner to explain it.
        val policy = RemoteImagePolicy.of(hasRemoteImages = false, sessionLoadImages = true)

        assertEquals(RemoteImagePolicy.Allowed, policy)
        assertTrue(policy.allowsNetworkLoads)
    }

    /**
     * The law that the old code broke. Over every input, the banner is shown only
     * when loading is blocked — an offer the app will not honour cannot exist.
     */
    @Test
    fun `banner and gate never both hold`() {
        val inputs = listOf(false, true)
        for (hasRemoteImages in inputs) {
            for (sessionLoadImages in inputs) {
                val policy = RemoteImagePolicy.of(hasRemoteImages, sessionLoadImages)
                assertFalse(
                    policy.showsBanner && policy.allowsNetworkLoads,
                    "banner and gate both true for " +
                        "hasRemoteImages=$hasRemoteImages sessionLoadImages=$sessionLoadImages"
                )
            }
        }
    }

    /**
     * The complementary law: tapping the banner must always change the outcome.
     * If accepting the offer left loads blocked, the tap would be a no-op — which
     * is precisely how the defect presented.
     */
    @Test
    fun `accepting the offer always unblocks loading`() {
        for (hasRemoteImages in listOf(false, true)) {
            val before = RemoteImagePolicy.of(hasRemoteImages, sessionLoadImages = false)
            if (!before.showsBanner) continue

            val after = RemoteImagePolicy.of(hasRemoteImages, sessionLoadImages = true)
            assertTrue(after.allowsNetworkLoads, "tapping the banner did not unblock loading")
            assertFalse(after.showsBanner, "banner survived its own acceptance")
        }
    }
}
