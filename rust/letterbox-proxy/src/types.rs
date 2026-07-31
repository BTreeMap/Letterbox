//! FFI record types exposed to Kotlin via UniFFI.
//!
//! These are plain data carriers; behaviour lives in [`crate`]. They are kept in
//! a dedicated module so `lib.rs` stays focused on the proxy logic.

/// A subresource fetched through the tunnel.
///
/// Named for what it carries rather than for what asked for it. This was
/// `ImageResponse`, and the name was load-bearing in the wrong direction: it
/// made "is this an image?" look like a question about the *transport*, so the
/// only path the renderer had to the tunnel refused a stylesheet and a font as
/// firmly as it would have refused an executable. A page needs all three, the
/// user consented to all three at once, and none of that is the fetch's
/// business — so the type says only that some bytes arrived and what they claim
/// to be.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FetchedResource {
    /// MIME type as the server declared it, lowercased and stripped of
    /// parameters (e.g. `image/png`, `text/css`, `font/woff2`).
    pub mime_type: String,
    /// Raw response bytes.
    pub data: Vec<u8>,
    /// Whether this response was served from cache.
    pub from_cache: bool,
    /// Final URL after redirects (if any).
    pub final_url: String,
}

/// Result of a generic tunnelled fetch.
///
/// Distinct from [`FetchedResource`] because it reports the HTTP `status` and is
/// not cached: its callers are the update check and the trace probe, which care
/// what the server answered and must never be served a stale answer.
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

/// Status of the proxy.
///
/// The `Default` is the pre-initialization status: nothing ready, nothing
/// connected, nothing cached.
#[derive(Clone, Debug, Default, uniffi::Record)]
pub struct ProxyStatus {
    /// Whether the proxy is initialized and ready.
    pub ready: bool,
    /// Whether WARP is enabled on this device.
    pub warp_enabled: bool,
    /// Whether the tunnel currently has a live session.
    pub tunnel_connected: bool,
    /// Current WARP endpoint (if provisioned).
    pub endpoint: Option<String>,
    /// Last error message (if any).
    pub last_error: Option<String>,
    /// Number of cached subresources.
    pub cache_size: u32,
}

/// What the live tunnel session is doing, for the in-app debug screen.
///
/// Strictly the session. Account identity is [`WarpStoredConfig`]'s and is not
/// duplicated here: the two records overlapped on ten fields, populated from
/// different sources, and the endpoint was where that showed — this one derived
/// its address from the live transport while its host came from the stored
/// WireGuard registration, so the screen displayed half of each.
#[derive(Clone, Debug, uniffi::Record)]
pub struct WarpDiagnostics {
    /// `"connected"` or `"disconnected"`.
    pub connection_state: String,
    /// Which transport is carrying the tunnel, currently always `"masque"`.
    ///
    /// Without this, "the tunnel is up" says nothing about *which* tunnel, so
    /// neither a test nor a bug report can distinguish MASQUE working from
    /// MASQUE having quietly fallen back.
    pub protocol: String,
    /// Address the session dials.
    ///
    /// A constant of the transport, not of the account: the registration API
    /// only ever returns WireGuard endpoints, so the MASQUE data plane
    /// (`162.159.198.0/24`) is hardcoded and cannot be read from the config.
    pub endpoint_address: String,
    /// UDP port the session dials.
    pub endpoint_port: u16,
    /// Name sent in the TLS ClientHello — the one identifier a passive observer
    /// can read, and deliberately not a `*.cloudflareclient.com` name.
    pub endpoint_sni: String,
    /// Seconds since the last completed handshake, if any.
    pub last_handshake_secs: Option<u64>,
    /// Plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
}

/// The persisted WARP *identity*, read straight from disk.
///
/// Unlike [`WarpDiagnostics`], building this never provisions or handshakes, so
/// it remains inspectable even when the tunnel is down — exactly the situation a
/// user needs visibility into.
///
/// It carries no endpoint. The registration API answers in WireGuard terms and
/// returns a `engage.cloudflareclient.com:2408` peer that this app never dials;
/// presenting it as "the endpoint" beside a transport row reading MASQUE was an
/// invitation to misread the screen. The address the session actually uses is a
/// property of the live session and lives in [`WarpDiagnostics`].
///
/// The `Default` is the "nothing provisioned" snapshot, so the unprovisioned
/// case does not have to spell out every empty field — a list in which a wrong
/// entry looks exactly like a right one.
#[derive(Clone, Debug, Default, uniffi::Record)]
pub struct WarpStoredConfig {
    /// Whether a provisioned WARP configuration exists on disk.
    pub has_config: bool,
    /// Whether a live tunnel manager is currently running.
    pub tunnel_active: bool,
    /// Cloudflare account/device identifier.
    pub account_id: String,
    /// Account license key (may be empty for free accounts). Sensitive.
    pub license_key: String,
    /// The 32 opaque bytes registration required, base64. Sensitive.
    ///
    /// Named for what it is: `POST /reg` will not mint a device without a `key`
    /// field, but nothing ever uses this one — the MASQUE session authenticates
    /// with the P-256 key enrolled afterwards. Calling it a "private key"
    /// implied the tunnel's security rested on it.
    pub registration_key: String,
    /// The endpoint's public key this device pins against: base64 SPKI DER.
    ///
    /// Empty on an account provisioned before MASQUE enrolment existed, which is
    /// exactly the state a bug report needs to show.
    pub pinned_endpoint_key: String,
    /// Local tunnel IPv4 address.
    pub local_address_ipv4: String,
    /// Whether WARP is enabled on the account.
    pub warp_enabled: bool,
    /// Account type (e.g. `free`).
    pub account_type: String,
    /// Unix timestamp (seconds) when the configuration was last provisioned.
    pub last_updated_secs: i64,
    /// Absolute path to the persisted `warp_config.json`.
    pub config_file_path: String,
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
///
/// The three outcome fields are a `Result` flattened for the FFI, which cannot
/// carry a Rust sum type per element. They are correlated, not independent:
/// `success` implies exactly one of `response`/`error` is set. Build them with
/// [`BatchImageResult::new`] so no caller can produce a combination — a
/// "successful" result with an error and no response — that the Kotlin side has
/// no sensible way to render.
#[derive(Clone, Debug, uniffi::Record)]
pub struct BatchImageResult {
    /// URL that was requested.
    pub url: String,
    /// Whether the fetch was successful.
    pub success: bool,
    /// Image response if successful.
    pub response: Option<FetchedResource>,
    /// Error message if failed.
    pub error: Option<String>,
}

impl BatchImageResult {
    /// Flatten one URL's outcome into the FFI record.
    pub(crate) fn new(url: String, outcome: Result<FetchedResource, crate::ProxyError>) -> Self {
        match outcome {
            Ok(response) => Self {
                url,
                success: true,
                response: Some(response),
                error: None,
            },
            Err(e) => Self {
                url,
                success: false,
                response: None,
                error: Some(e.to_string()),
            },
        }
    }
}
