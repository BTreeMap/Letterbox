# Image Proxy Design Document

## Overview

This document describes the design of the privacy-preserving image proxy for Letterbox. The proxy fetches remote images through Cloudflare's WARP network, hiding the user's IP address from image servers.

## Problem Statement

Traditional image proxy approaches have several issues:
- **Format limitations**: Third-party proxies may not support all image formats (SVG, WebP, etc.)
- **Dependency on third-party service**: Reliance on external service availability and behavior
- **Limited control**: No control over caching, rate limiting, or error handling
- **Privacy concerns**: Third-party proxies can still log requests

## Solution Architecture

We implement a fully in-app image proxy using:
1. **Cloudflare WARP** for network anonymization
2. **MASQUE** or **WireGuard** for encrypted tunnel transport (see below)
3. **smoltcp** for userspace TCP/IP networking
4. **rustls** for TLS/HTTPS connections

### Two transports, one interface

Both transports do the same job — take an IP packet, get it to Cloudflare, bring
replies back — and differ only in how they wrap it. `tunnel::link::Link` is a
closed enum over the two, so everything above it (`stack`, `tls`, `http1`,
`dns`) is written once and is unaware of which is carrying it.

| | WireGuard | MASQUE |
|---|---|---|
| Wire format | WireGuard over UDP | CONNECT-IP in QUIC datagrams, HTTP/3 |
| Port | UDP 500 | UDP 443 |
| Looks like | WireGuard | ordinary HTTP/3 |
| Credential | X25519, from registration | P-256, enrolled by PATCH |
| Implementation | boringtun | `usque-core` (derived from usque-rs) |

MASQUE is preferred wherever the account has enrolled credentials. WireGuard is
the fallback for accounts provisioned before MASQUE support existed and for
enrolments that fail — a blocked-but-configured tunnel beats no tunnel, and the
alternative is a proxy that refuses to start. Existing accounts enrol a MASQUE
key on their next connect, so no install is stranded on WireGuard.

**Known limitation.** The choice is made once, from stored credentials, before
either transport has tried to connect. An account with MASQUE credentials on a
network that blocks UDP/443 therefore fails rather than retrying over WireGuard.
This is deliberate for now — QUIC on 443 is far less likely to be blocked than
WireGuard on 500, which is the whole reason for the migration — but it does mean
the fallback is a provisioning-time fallback, not a connectivity-time one. A
failed MASQUE session surfaces as an error from `poll_incoming` rather than
hanging, so the failure is at least prompt.

The reason for the migration is firewall compatibility: WireGuard's handshake is
trivially fingerprinted and widely blocked, while MASQUE traffic is
indistinguishable from any other QUIC flow on 443.

#### The SNI is deliberately not Cloudflare's

The tunnel sends **`api.cloudflare.com`** in its TLS ClientHello, not the
`consumer-masque.cloudflareclient.com` that reference implementations use.

The SNI is the only name a passive observer can read: the `:authority` on the
CONNECT request travels inside the encrypted HTTP/3 stream. Any
`*.cloudflareclient.com` name would label the connection as WARP to anything
watching, which defeats the point of tunnelling.

Substituting a name is safe because peer identity does not depend on it. The
session runs with `verify_peer(false)` and pins the endpoint by its
`SubjectPublicKeyInfo`, so the certificate's name is never consulted. Whether
the endpoint *serves* an unexpected SNI is a server-side policy question, and is
verified empirically rather than assumed.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Kotlin/Android UI                             │
│                                                                         │
│  ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐    │
│  │ EmailDetailScreen│   │ UserPreferences  │   │    ProxyStatus   │    │
│  │                  │   │    Repository    │   │      Banner      │    │
│  └────────┬─────────┘   └────────┬─────────┘   └────────┬─────────┘    │
│           │                      │                      │              │
└───────────┼──────────────────────┼──────────────────────┼──────────────┘
            │                      │                      │
            │ UniFFI               │                      │
            ▼                      ▼                      ▼
┌───────────────────────────────────────────────────────────────────────┐
│                          letterbox-proxy (Rust)                        │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                         FFI API Layer                            │   │
│  │  proxy_init() | proxy_status() | proxy_fetch_image() | ...       │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │                                     │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │                        Image Fetcher                             │   │
│  │  - URL validation          - Content-type checking               │   │
│  │  - Size limits             - Redirect following                  │   │
│  │  - LRU caching             - Cookie stripping                    │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │                                     │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │                     TLS Layer (rustls)                           │   │
│  │  - TLS 1.3 for HTTPS        - Certificate validation             │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │                                     │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │                   TCP/IP Stack (smoltcp)                         │   │
│  │  - Full TCP implementation   - Connection management             │   │
│  │  - Userspace networking      - No kernel/VPN required            │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │                                     │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │                WireGuard Transport (boringtun)                   │   │
│  │  - Encryption/decryption     - Handshake management              │   │
│  │  - Keepalive handling        - Packet encapsulation              │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │                                     │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │                   WARP Provisioning                              │   │
│  │  - Account creation          - Configuration fetch               │   │
│  │  - Key generation            - Credential storage                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────────┘
                                  │
                                  │ UDP (WireGuard encrypted)
                                  ▼
┌───────────────────────────────────────────────────────────────────────┐
│                     Cloudflare WARP Infrastructure                     │
│                                                                         │
│  engage.cloudflareclient.com:2408                                      │
│  162.159.192.1 / 2606:4700:d0::a29f:c001                               │
└───────────────────────────────────────────────────────────────────────┘
```

## Components

### 1. WARP Provisioning (`provisioning.rs`)

Handles the creation and management of Cloudflare WARP identities.

#### Registration Flow

```
1. Generate X25519 keypair locally
2. POST /reg with public key and timestamp
3. Receive account_id and access_token
4. GET /reg/{account_id} for tunnel configuration
5. PATCH /reg/{account_id} to enable WARP
6. Store credentials securely
```

#### API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/v0a884/reg` | POST | Register new device |
| `/v0a884/reg/{id}` | GET | Fetch configuration |
| `/v0a884/reg/{id}` | PATCH | Enable/disable WARP |

#### Persisted Data

```rust
struct WarpAccountData {
    account_id: String,      // Device identifier
    access_token: String,    // Bearer token for API
    private_key: String,     // WireGuard private key (base64)
    license_key: String,     // For WARP+ subscribers
}
```

### 2a. MASQUE Transport (`tunnel/masque.rs`)

CONNECT-IP over HTTP/3, using the vendored `usque-core` crate. The session loop
is `usque_core::run_tunnel_session`, unchanged from usque-rs apart from the
edits listed in `rust/usque-core/PROVENANCE.md`.

That loop is async and owns its socket; the rest of the tunnel is a blocking
poll loop on one worker thread. Rather than convert either side, the session
runs on its own thread with a current-thread runtime, bridged by
`tunnel::duplex::PacketDuplex`.

#### Packet framing is the load-bearing detail

`run_tunnel_session` was written against a TUN device, where one `read` returns
exactly one IP packet. `AsyncRead` makes no such promise — a byte stream may
split or coalesce — but the loop hands whatever one read produced straight to
`dgram_send_vec` as a single datagram.

`PacketDuplex` restores that guarantee. A packet too large for the caller's
buffer is **dropped, not truncated**: a partial packet is not a smaller packet
but a malformed one, and forwarding it would put corrupt bytes on the wire under
a valid-looking length.

Both queues are bounded and drop on overflow rather than blocking. IP is lossy
by contract and TCP above retransmits, whereas blocking would stall the loop
that also drives QUIC timers — losing a packet costs a retransmit, stalling the
loop costs the connection.

### 2b. WireGuard Transport (`transport.rs`)

Implements userspace WireGuard using boringtun (Mullvad's fork of Cloudflare's BoringTun).

#### Key Features

- **Handshake management**: Initiates and responds to WireGuard handshakes
- **Encryption**: Uses ChaCha20-Poly1305 for packet encryption
- **Keepalives**: Sends periodic keepalives to maintain tunnel
- **Non-blocking**: Uses async UDP sockets for efficient polling

#### Integration Loop

```
while running {
    // Receive encrypted from UDP
    let encrypted = socket.recv();
    let decrypted = tunn.decapsulate(encrypted);
    
    // Feed to smoltcp
    smoltcp_device.rx_queue.push(decrypted);
    
    // Poll smoltcp
    interface.poll(&mut sockets);
    
    // Send outgoing
    while let Some(ip_packet) = smoltcp_device.tx_queue.pop() {
        let encrypted = tunn.encapsulate(ip_packet);
        socket.send(encrypted);
    }
    
    // Periodic maintenance
    tunn.update_timers();
}
```

### 3. TCP/IP Stack (`tunnel.rs`)

Uses smoltcp for userspace TCP/IP networking without kernel involvement.

#### Virtual Device

A custom smoltcp `Device` bridges the TCP/IP stack with WireGuard:

```rust
struct VirtualDevice {
    rx_queue: VecDeque<Vec<u8>>,  // From WireGuard -> smoltcp
    tx_queue: VecDeque<Vec<u8>>,  // From smoltcp -> WireGuard
}
```

#### Socket Management

- Pre-allocated socket storage for up to 16 concurrent connections
- Socket handles returned to callers for read/write operations
- Automatic cleanup on connection close

### 4. HTTP Client (`http.rs`)

Fetches images with strict privacy and security controls.

#### Privacy Features

| Feature | Implementation |
|---------|----------------|
| Cookie stripping | No cookie jar, don't send/store cookies |
| Referrer blocking | `referer(false)` in client builder |
| User agent | Generic "ImageProxy/1.0" |
| IP hiding | All traffic through WARP tunnel |

#### Security Controls

| Control | Default | Purpose |
|---------|---------|---------|
| Max size | 10 MB | Prevent DoS via large images |
| Max redirects | 5 | Prevent redirect loops |
| Timeout | 30s | Prevent hanging connections |
| Content-type | image/* only | Prevent non-image responses |

#### Supported Image Types

- `image/jpeg`
- `image/png`
- `image/gif`
- `image/webp`
- `image/svg+xml`
- `image/bmp`
- `image/x-icon`
- `image/vnd.microsoft.icon`

### 5. Caching

In-memory LRU cache with configurable size:

```rust
cache: LruCache<String, ImageResponse>
```

Cache keys are normalized URLs. Responses include MIME type, data, and final URL.

## FFI API

The API is designed for maximum parallelism since emails often contain many small images.

### Functions

```rust
// Initialize the proxy
fn proxy_init(storage_path: String, max_cache_size: u32) -> Result<(), ProxyError>

// Get current status
fn proxy_status() -> Result<ProxyStatus, ProxyError>

// Fetch single image
fn proxy_fetch_image(url: String, headers: Option<HashMap<String, String>>) 
    -> Result<ImageResponse, ProxyError>

// Fetch multiple images in parallel
fn proxy_fetch_images_batch(urls: Vec<String>, max_concurrent: u32) 
    -> Result<Vec<BatchImageResult>, ProxyError>

// Clean shutdown
fn proxy_shutdown() -> Result<(), ProxyError>

// Clear cache
fn proxy_clear_cache() -> Result<(), ProxyError>
```

### Batch Processing

The `proxy_fetch_images_batch` function uses a semaphore-limited thread pool:

```rust
let semaphore = Semaphore::new(max_concurrent);
for url in urls {
    spawn(async {
        let _permit = semaphore.acquire().await;
        fetch_image_internal(&url).await
    });
}
```

## Error Handling

### Error Types

| Error | Cause | Recovery |
|-------|-------|----------|
| `NotInitialized` | Called before `proxy_init()` | Call `proxy_init()` first |
| `ProvisioningFailed` | WARP API error | Retry with backoff |
| `TunnelError` | WireGuard handshake failed | Retry connection |
| `InvalidUrl` | Malformed URL | Return error to caller |
| `HttpError` | HTTP status != 2xx | Return error with status |
| `InvalidContentType` | Not an image | Return error |
| `ResponseTooLarge` | Exceeds size limit | Return error |
| `TooManyRedirects` | Redirect loop | Return error |
| `Timeout` | Request timed out | Retry |

### Graceful Degradation

If the tunnel cannot be established, the HTTP client falls back to direct requests (with privacy implications noted to the user).

## Security Considerations

### Key Management

- Private keys generated using cryptographically secure RNG
- Keys stored in app-private storage directory
- Keys never transmitted (only public key sent to Cloudflare)

### Network Security

- All image traffic encrypted with WireGuard
- TLS 1.3 for HTTPS connections
- Certificate validation via rustls

### Input Validation

- URL scheme validation (http/https only)
- Content-type validation before parsing
- Size limits enforced during download
- Magic byte verification for binary formats

## Legal Considerations

Users must agree to Cloudflare's Terms of Service before enabling the proxy:

> "Images are fetched through Cloudflare infrastructure; by enabling this, you agree to Cloudflare's legal terms."

Link: https://www.cloudflare.com/application/terms/

## Future Enhancements

1. **HTTP/2 support**: Multiplexed connections for faster parallel fetches
2. **Persistent disk cache**: Survive app restarts
3. **Compression**: Compress cached images
4. **Metrics**: Track bandwidth, cache hit rate, error rates

> DNS-over-HTTPS (resolving hostnames through the tunnel via Cloudflare
> `1.1.1.1`) is already implemented in `src/tunnel/dns.rs`.

## Dependencies

Versions below are indicative; `rust/letterbox-proxy/Cargo.toml` is the source of
truth (Renovate keeps it current).

| Crate | Version | Purpose |
|-------|---------|---------|
| boringtun | 0.7 | WireGuard implementation |
| smoltcp | 0.13 | TCP/IP stack |
| rustls | 0.23 | TLS library |
| reqwest | 0.13 | HTTP client (for provisioning) |
| x25519-dalek | 2.0 | Key generation |
| uniffi | 0.31 | FFI bindings |
| tokio | 1.49 | Async runtime |
| lru | 0.18 | Cache implementation |

## Testing Strategy

### Unit Tests

- Configuration serialization/deserialization
- Key generation and parsing
- URL validation
- Content-type checking
- Error handling
- Virtual device behavior
- Tunnel creation

### Integration Tests

- WARP provisioning flow (with mock server)
- WireGuard handshake (local loopback)
- HTTP fetching through tunnel
- Cache behavior

### End-to-End Tests

- Full image fetch from public URL
- Batch image fetching
- Error recovery scenarios
- Cache hit/miss verification
