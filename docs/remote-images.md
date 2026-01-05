# Remote Image Loading and Privacy Proxy

## Overview

Letterbox provides comprehensive privacy protection for loading remote images in emails. The app uses a privacy-preserving image proxy based on Cloudflare WARP:

- **Cloudflare WARP Proxy**: Uses a WireGuard tunnel through Cloudflare's network to hide your IP address

## Features

### Default Behavior
- Remote images (http:// and https://) are **blocked by default** to protect your privacy
- When an email contains remote images, a banner appears: "Remote images are hidden"
- Click "Show" to load images for the current email only

### Privacy Protection
- When images are loaded, they're routed through the privacy proxy
- This prevents the email sender from seeing your IP address
- Tracking headers (Referer, User-Agent) are stripped
- Cookies are never sent to image servers

### Settings

Access Settings from the main screen menu:

#### Always load remote images
- **Default: OFF**
- When enabled, remote images will load automatically in all emails
- Images are still proxied for privacy

#### Use privacy proxy
- **Default: ON**
- When enabled, all remote images are loaded through the privacy proxy
- When disabled, images load directly from their source (not recommended - exposes your IP)

## Technical Details

### Cloudflare WARP Proxy Architecture

The proxy implementation uses a full WireGuard tunnel through Cloudflare's WARP network:

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

#### Components
- **boringtun**: WireGuard implementation for encrypted transport
- **smoltcp**: Userspace TCP/IP stack
- **rustls**: TLS 1.3 for HTTPS connections
- **LRU cache**: In-memory caching of fetched images

#### Key Benefits
- **Works with all image formats** including SVG
- **Consistent performance**: Direct control over the tunnel
- **Better error handling**: Detailed error messages for debugging
- **Parallel fetching**: Optimized for emails with many images

### Privacy Benefits
1. **IP Masking**: Your IP address is not visible to the email sender
2. **Header Stripping**: Tracking headers are removed
3. **Cookie Blocking**: Cookies are never sent or stored
4. **No JavaScript**: The WebView keeps JavaScript disabled for security

### Inline Images (cid: URLs)
- Inline images embedded in the email are always displayed
- These are not affected by the remote image settings
- No network access is required for inline images

## Security Considerations

- The WebView remains sandboxed with:
  - JavaScript disabled
  - File access disabled
  - Network loads only enabled when explicitly requested
- SAF (Storage Access Framework) permissions are persisted when opening files
- Only specific MIME types are accepted by the file picker

### Error Handling

When the native Rust library is unavailable or encounters an error:
- The original HTML is displayed without modification
- The app does not crash - errors are caught and handled gracefully
- Inline (cid:) images continue to work normally

## Implementation

The feature is implemented in several layers:

1. **UserPreferencesRepository**: Persists settings using Jetpack DataStore
2. **letterbox-proxy**: Rust crate that handles image fetching through WARP tunnel
3. **EmailViewModel**: Tracks session image loading state and detects remote images
4. **EmailDetailScreen**: Displays privacy banner and controls image loading
5. **WebView**: Configured to allow network loads only when appropriate

## Testing

Run the Rust proxy tests:
```bash
cargo test --manifest-path rust/letterbox-proxy/Cargo.toml
```

Run the Kotlin unit tests:
```bash
./gradlew :app:testProdDebugUnitTest
```

Test coverage includes:
- URL validation and content type checking
- WARP configuration and persistence
- WireGuard tunnel creation
- Cache behavior
- Error handling scenarios
