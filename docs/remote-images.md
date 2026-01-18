# Remote Image Loading and Privacy Proxy

## Overview

Letterbox provides privacy protection when loading remote images in emails. The app uses a WireGuard-based proxy through Cloudflare WARP to hide your IP address from image servers.

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

When you first enable the privacy proxy, you will be asked to accept Cloudflare's Terms of Service. This is required because images are fetched through Cloudflare's WARP infrastructure. 

The Terms of Service dialog:
- Explains that images are fetched through Cloudflare WARP
- Provides a link to view Cloudflare's Terms of Service
- Requires explicit acceptance before the proxy is enabled

Once you accept the terms, the proxy can be enabled or disabled without showing the dialog again.

## Technical Details

### Architecture

The proxy implementation uses a WireGuard tunnel through Cloudflare WARP:

```
┌───────────────┐      ┌──────────────┐      ┌──────────────┐      ┌─────────────┐
│  Image URL    │ ──▶  │  letterbox   │ ──▶  │  Cloudflare  │ ──▶  │   Image     │
│  from Email   │      │    proxy     │      │    WARP      │      │   Server    │
└───────────────┘      └──────────────┘      └──────────────┘      └─────────────┘
                              │
                    ┌─────────┴─────────┐
                    │    WireGuard      │
                    │    (encrypted)    │
                    └───────────────────┘
```

### Components

| Component | Purpose |
|-----------|---------|
| boringtun | WireGuard implementation for encrypted transport |
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
| `INTERNET` | Required for the WireGuard tunnel to communicate with Cloudflare WARP endpoints |

**Why INTERNET permission is needed:** The privacy proxy creates a WireGuard tunnel using UDP sockets to encrypt traffic and route it through Cloudflare. Without INTERNET permission, the proxy cannot establish network connections.

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
- WireGuard tunnel creation
- Cache behavior
- Error handling scenarios
- Remote image banner display and interaction
- Cloudflare ToS consent flow
- Settings persistence across app restarts
