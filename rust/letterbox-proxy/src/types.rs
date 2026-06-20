//! FFI record types exposed to Kotlin via UniFFI.
//!
//! These are plain data carriers; behaviour lives in [`crate`]. They are kept in
//! a dedicated module so `lib.rs` stays focused on the proxy logic.

/// Result of a successful image fetch operation.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ImageResponse {
    /// MIME type of the image (e.g., "image/png", "image/svg+xml").
    pub mime_type: String,
    /// Raw image bytes.
    pub data: Vec<u8>,
    /// Whether this response was served from cache.
    pub from_cache: bool,
    /// Final URL after redirects (if any).
    pub final_url: String,
}

/// Result of a generic tunnelled fetch (non-image content).
#[derive(Clone, Debug, uniffi::Record)]
pub struct HttpFetchResponse {
    /// HTTP status code of the final response.
    pub status: u16,
    /// Normalised MIME type.
    pub mime_type: String,
    /// Raw response body.
    pub data: Vec<u8>,
    /// Final URL after redirects.
    pub final_url: String,
}

/// Status of the image proxy.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ProxyStatus {
    /// Whether the proxy is initialized and ready.
    pub ready: bool,
    /// Whether WARP is enabled on this device.
    pub warp_enabled: bool,
    /// Whether the WireGuard tunnel currently has a live session.
    pub tunnel_connected: bool,
    /// Current WireGuard endpoint (if provisioned).
    pub endpoint: Option<String>,
    /// Last error message (if any).
    pub last_error: Option<String>,
    /// Number of cached images.
    pub cache_size: u32,
}

/// Full WireGuard/WARP diagnostics for the in-app debug screen.
///
/// This intentionally includes the private key so power users can fully inspect
/// and reproduce the tunnel; the Android UI hides it behind an explicit reveal.
#[derive(Clone, Debug, uniffi::Record)]
pub struct WarpDiagnostics {
    /// `"connected"` or `"disconnected"`.
    pub connection_state: String,
    /// WireGuard private key (base64).
    pub private_key: String,
    /// Derived WireGuard public key (base64).
    pub public_key: String,
    /// WARP peer public key (base64).
    pub peer_public_key: String,
    /// Endpoint hostname.
    pub endpoint_host: String,
    /// Endpoint IPv4 address.
    pub endpoint_ipv4: String,
    /// Endpoint IPv6 address.
    pub endpoint_ipv6: String,
    /// Endpoint UDP port.
    pub endpoint_port: u16,
    /// Local tunnel IPv4 address.
    pub local_address_ipv4: String,
    /// Local tunnel IPv6 address.
    pub local_address_ipv6: String,
    /// Whether WARP is enabled on the account.
    pub warp_enabled: bool,
    /// Account type (e.g. `free`).
    pub account_type: String,
    /// Cloudflare account/device identifier.
    pub account_id: String,
    /// Seconds since the last completed handshake, if any.
    pub last_handshake_secs: Option<u64>,
    /// Plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
    /// Estimated packet loss in `[0.0, 1.0]`.
    pub estimated_loss: f32,
    /// Estimated round-trip time in milliseconds, if measured.
    pub rtt_ms: Option<u32>,
}

/// Result of an in-app update check.
#[derive(Clone, Debug, uniffi::Record)]
pub struct UpdateResult {
    /// Whether a newer release is available.
    pub update_available: bool,
    /// Running version as reported by the caller.
    pub current_version: String,
    /// Latest release version (no leading `v`).
    pub latest_version: String,
    /// Latest release git tag.
    pub latest_tag: String,
    /// Release notes.
    pub changelog: String,
    /// Release page URL.
    pub release_url: String,
}

/// Result of a batch image fetch operation.
#[derive(Clone, Debug, uniffi::Record)]
pub struct BatchImageResult {
    /// URL that was requested.
    pub url: String,
    /// Whether the fetch was successful.
    pub success: bool,
    /// Image response if successful.
    pub response: Option<ImageResponse>,
    /// Error message if failed.
    pub error: Option<String>,
}
