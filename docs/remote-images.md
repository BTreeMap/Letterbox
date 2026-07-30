# Remote Image Loading and Privacy Proxy

## Overview

Letterbox provides privacy protection when loading remote images in emails. The app uses a MASQUE proxy through Cloudflare WARP to hide your IP address from image servers.

## Features

### Default Behavior

- Remote images (HTTP and HTTPS URLs) are blocked by default to protect privacy.
- When an email contains remote images, a banner displays "Remote images are hidden."
- Tap "Show" to load images for the current email session.

### Privacy Protection

When images load through the proxy:
- Your IP address remains hidden from the email sender.
- Tracking headers (Referer, User-Agent) are stripped.
- Cookies are never sent to image servers.

### Settings

Access Settings from the main screen menu:

#### Always load remote images
- **Default: OFF**
- When enabled, remote images load automatically in all emails.
- Images are still proxied for privacy.

#### Use privacy proxy
- **Default: ON**
- When enabled, all remote images load through the privacy proxy.
- When disabled, images load directly from their source (exposes your IP address).

### Cloudflare WARP Terms of Service

Settings carries a standing disclosure beside the privacy-proxy switch: loading
a remote image sends the request through the Cloudflare WARP tunnel, subject to
Cloudflare's Terms of Service, with a link to read them.

There is no separate acceptance step. Loading an image is itself the opt-in —
remote images are blocked by default and require either a per-message "Show
images" tap or an explicit setting — so a second confirmation adds a condition
without adding a choice.

It also caused a total failure. A `cloudflareTermsAccepted` flag was AND-ed into
the WebView's network gate, while the "Show images" banner that sets the other
half tested only itself. Tapping the banner retired it and left the gate shut,
so every image failed with no surface anywhere explaining why, and anyone who
had onboarded before the flag existed had it false permanently. The gate is now
a single value, `RemoteImagePolicy`, that both the banner and the WebView read —
an offer the app will not honour is unrepresentable.

## Technical Details

### Architecture

The proxy implementation uses a MASQUE tunnel (CONNECT-IP over HTTP/3) through Cloudflare WARP:

```
┌───────────────┐      ┌──────────────┐      ┌──────────────┐      ┌─────────────┐
│  Image URL    │ ──▶  │  letterbox   │ ──▶  │  Cloudflare  │ ──▶  │   Image     │
│  from Email   │      │    proxy     │      │    WARP      │      │   Server    │
└───────────────┘      └──────────────┘      └──────────────┘      └─────────────┘
                              │
                    ┌─────────┴─────────┐
                    │  MASQUE / QUIC    │
                    │  (UDP 443, TLS)   │
                    └───────────────────┘
```

### Components

| Component | Purpose |
|-----------|---------|
| usque-core (quiche) | MASQUE CONNECT-IP over HTTP/3 |
| smoltcp | Userspace TCP/IP stack |
| rustls | TLS 1.3 for HTTPS connections |
| LRU cache | In-memory caching of fetched images |

### Benefits

- Supports all image formats including SVG and WebP.
- Provides consistent performance with direct tunnel control.
- Enables detailed error messages for debugging.
- Optimizes for emails with many images through parallel fetching.

### Privacy Features

1. **IP Masking**: Your IP address is not visible to the email sender.
2. **Header Stripping**: Tracking headers are removed.
3. **Cookie Blocking**: Cookies are never sent or stored.
4. **No JavaScript**: The WebView keeps JavaScript disabled for security.

### Inline Images (cid: URLs)

- Inline images embedded in the email always display.
- These are not affected by the remote image settings.
- No network access is required for inline images.

## Link Handling

The email WebView provides conventional link interaction:

### Clicking Links
- **HTTP/HTTPS links**: Open in the default browser app
- **mailto: links**: Open in the default email client

### Long-Press Context Menu
Long-pressing a link or image shows a context menu with options:
- **Links**: "Open link" or "Copy link address"
- **Images**: "Open image" or "Copy image URL"

This provides a familiar user experience while maintaining security by opening external content outside the app sandbox.

## Security

The WebView remains sandboxed with:
- JavaScript disabled
- File access disabled
- Network loads intercepted and proxied through WARP

Storage Access Framework (SAF) permissions are persisted when opening files. Only specific MIME types are accepted by the file picker.

### Error Handling

When the native Rust library is unavailable or encounters an error:
- The original HTML displays without modification.
- The app does not crash; errors are caught and handled gracefully.
- Inline (cid:) images continue to work normally.
- If proxy fails, images display an error placeholder.

### Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Required for the MASQUE tunnel to communicate with Cloudflare WARP endpoints |

**Why INTERNET permission is needed:** The privacy proxy creates a MASQUE tunnel over QUIC (UDP/443) to encrypt traffic and route it through Cloudflare. Without INTERNET permission, the proxy cannot establish network connections.

**Privacy remains protected because:**
1. Your IP address is hidden behind Cloudflare's infrastructure
2. No tracking headers are sent
3. Cookies are blocked
4. The proxy only fetches images - it doesn't browse or track

## Implementation Layers

1. **UserPreferencesRepository**: Persists settings using Jetpack DataStore.
2. **ImageProxyService**: Kotlin service that wraps the Rust FFI for image fetching.
3. **letterbox-proxy**: Rust crate that handles image fetching through WARP tunnel.
4. **EmailDetailScreen**: Displays privacy banner and controls image loading.
5. **EmailWebView**: Intercepts HTTP/HTTPS requests and routes through the proxy.

## Testing

Run the Rust proxy tests:
```bash
cargo test --manifest-path rust/letterbox-proxy/Cargo.toml
```

Run the Kotlin unit tests:
```bash
./gradlew :app:testProdDebugUnitTest
```

Run the Android instrumented tests:
```bash
./gradlew :app:connectedProdDebugAndroidTest
```

Test coverage includes:
- URL validation and content type checking
- WARP configuration and persistence
- MASQUE tunnel creation
- Cache behavior
- Error handling scenarios
- Remote image banner display and interaction
- `RemoteImagePolicy`: the banner is offered exactly when a tap would unblock
  loading, checked over the whole input space rather than one path
- Settings persistence across app restarts

### Known coverage gap

`RemoteImageWarpE2ETest` calls `ImageProxyService.fetchImage` directly, so it
proves the tunnel and the proxy work but never crosses the WebView gate. The
2026-07-30 failure lived exactly there — in the predicate deciding whether the
WebView was allowed to ask — so that test passed throughout while no image in
any email loaded.

`RemoteImagePolicyTest` now covers the decision as an algebra, which is the part
that was wrong. What remains untested end to end is the *wiring*: that
`EmailDetailScreen` passes `policy.allowsNetworkLoads` into `EmailWebView`, and
that `EmailWebView` turns it into `blockNetworkLoads = !allow`. An instrumented
test that taps "Show images" and asserts `webView.settings.blockNetworkLoads`
became `false` would close it, and is the test whose absence let this ship.
