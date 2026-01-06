# letterbox-proxy

Privacy-preserving image proxy for Letterbox using Cloudflare WARP over WireGuard.

## Overview

This crate provides a complete image proxy solution that fetches remote images through Cloudflare's WARP network, hiding the user's IP address from image servers.

Key features:
- Supports all image formats including SVG, WebP, and ICO.
- Provides consistent performance with in-app control.
- Uses a per-user WARP identity for privacy.
- No dependency on third-party image proxy services.

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

| Feature | Description |
|---------|-------------|
| Key Generation | Creates X25519 keypairs for WireGuard |
| Registration | Registers new device with Cloudflare API |
| Configuration | Fetches tunnel configuration (endpoints, addresses) |
| Persistence | Stores credentials in app-private storage |

### WireGuard Transport (`transport.rs`)

Implements the WireGuard tunnel using boringtun:

| Feature | Description |
|---------|-------------|
| Encryption | All traffic encrypted with WireGuard |
| Handshake | Handles key exchange with WARP endpoints |
| Keepalive | Maintains tunnel connectivity |

### TCP/IP Stack (`tunnel.rs`)

Uses smoltcp for userspace networking:

| Feature | Description |
|---------|-------------|
| TCP Connections | Full TCP stack without kernel involvement |
| Virtual Device | Bridges smoltcp with WireGuard |
| Connection Management | Handles multiple concurrent connections |

### HTTP Client (`http.rs`)

Fetches images with privacy protections:

| Feature | Description |
|---------|-------------|
| Cookie Stripping | Never sends or stores cookies |
| Referrer Blocking | No referrer headers sent |
| Content Validation | Checks MIME types and magic bytes |
| Size Limits | Prevents DoS via large responses |

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

## Privacy

| Feature | Description |
|---------|-------------|
| IP Masking | User's IP is never visible to image servers |
| No Tracking | Cookies and referrers are stripped |
| Encrypted Transit | All traffic is WireGuard-encrypted |
| Per-User Identity | Each user has their own WARP identity |
| No DNS Leaks | DNS is resolved through the tunnel |

## Legal

By enabling this feature, users agree to Cloudflare's terms of service:
https://www.cloudflare.com/application/terms/

## Dependencies

| Crate | Purpose |
|-------|---------|
| boringtun | WireGuard implementation |
| smoltcp | TCP/IP stack |
| rustls | TLS 1.3 implementation |
| reqwest | HTTP client (for provisioning) |
| x25519-dalek | Curve25519 key generation |
