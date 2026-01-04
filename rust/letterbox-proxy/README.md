# letterbox-proxy

Privacy-preserving image proxy for Letterbox using Cloudflare WARP over WireGuard.

## Overview

This crate provides a complete image proxy solution that fetches remote images through Cloudflare's WARP network, hiding the user's IP address from image servers. Unlike the previous DuckDuckGo proxy approach, this implementation:

- Works with all image formats including SVG
- Provides consistent performance
- Uses a per-user WARP identity for better privacy

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Kotlin/Android UI                             │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ UniFFI
┌───────────────────────────────────▼─────────────────────────────────────┐
│                          letterbox-proxy (Rust)                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐   │
│  │   Image     │  │    HTTP     │  │    TLS      │  │    Cache     │   │
│  │   Fetcher   │──│   Client    │──│   Layer     │──│   Manager    │   │
│  └─────────────┘  └──────┬──────┘  └─────────────┘  └──────────────┘   │
│                          │                                              │
│  ┌───────────────────────▼───────────────────────────────────────────┐  │
│  │                        smoltcp TCP/IP Stack                        │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
│                                  │                                      │
│  ┌───────────────────────────────▼───────────────────────────────────┐  │
│  │                  WireGuard (boringtun) Transport                   │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
│                                  │ UDP                                  │
│  ┌───────────────────────────────▼───────────────────────────────────┐  │
│  │                      WARP Endpoint (Cloudflare)                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Components

### WARP Provisioning (`provisioning.rs`)

Handles Cloudflare WARP account creation and management:

- **Key Generation**: Creates X25519 keypairs for WireGuard
- **Registration**: Registers new device with Cloudflare API
- **Configuration**: Fetches tunnel configuration (endpoints, addresses)
- **Persistence**: Stores credentials in app-private storage

### WireGuard Transport (`transport.rs`)

Implements the WireGuard tunnel using boringtun:

- **Encryption**: All traffic is encrypted with WireGuard
- **Handshake**: Handles key exchange with WARP endpoints
- **Keepalive**: Maintains tunnel connectivity

### TCP/IP Stack (`tunnel.rs`)

Uses smoltcp for userspace networking:

- **TCP Connections**: Full TCP stack without kernel involvement
- **Virtual Device**: Bridges smoltcp with WireGuard
- **Connection Management**: Handles multiple concurrent connections

### HTTP Client (`http.rs`)

Fetches images with privacy protections:

- **Cookie Stripping**: Never sends or stores cookies
- **Referrer Blocking**: No referrer headers
- **Content Validation**: Checks MIME types and magic bytes
- **Size Limits**: Prevents DoS via large responses

## FFI API

```rust
// Initialize the proxy
fn proxy_init(storage_path: String, max_cache_size: u32) -> Result<(), ProxyError>

// Get proxy status
fn proxy_status() -> Result<ProxyStatus, ProxyError>

// Fetch a single image
fn proxy_fetch_image(url: String, headers: Option<HashMap<String, String>>) -> Result<ImageResponse, ProxyError>

// Fetch multiple images in parallel
fn proxy_fetch_images_batch(urls: Vec<String>, max_concurrent: u32) -> Result<Vec<BatchImageResult>, ProxyError>

// Shut down the proxy
fn proxy_shutdown() -> Result<(), ProxyError>

// Clear the cache
fn proxy_clear_cache() -> Result<(), ProxyError>
```

## Building

```bash
# Build for host (for tests)
cargo build --release

# Build for Android via cargo-ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o /path/to/jniLibs build --release
```

## Testing

```bash
# Run unit tests
cargo test

# Run with logging
RUST_LOG=debug cargo test -- --nocapture
```

## Privacy Considerations

1. **IP Masking**: User's IP is never visible to image servers
2. **No Tracking**: Cookies and referrers are stripped
3. **Encrypted Transit**: All traffic is WireGuard-encrypted
4. **Per-User Identity**: Each user has their own WARP identity
5. **No DNS Leaks**: DNS is resolved through the tunnel

## Legal

By enabling this feature, users agree to Cloudflare's terms of service:
https://www.cloudflare.com/application/terms/

## Dependencies

- `boringtun`: WireGuard implementation
- `smoltcp`: TCP/IP stack
- `rustls`: TLS 1.3 implementation
- `reqwest`: HTTP client (for provisioning)
- `x25519-dalek`: Curve25519 key generation
