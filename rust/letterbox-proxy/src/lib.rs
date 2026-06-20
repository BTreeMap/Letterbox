//! # Letterbox Image Proxy
//!
//! Privacy-preserving image proxy and update checker built on Cloudflare WARP
//! over a userspace WireGuard tunnel.
//!
//! Every outbound HTTP(S) request — remote images *and* the GitHub update
//! check — is carried over the tunnel ([`tunnel`]). There is no direct,
//! non-tunnelled network path in the fetch flow, so the user's real IP address
//! is never exposed to image servers or to GitHub. The only traffic that leaves
//! the device unwrapped is the one-time WARP *registration* with Cloudflare's
//! own API, which is intrinsic to obtaining WARP credentials.
//!
//! ## Pipeline
//!
//! ```text
//! FFI -> TunnelManager (worker thread) -> http -> tls/dns -> smoltcp -> WireGuard -> UDP
//! ```
//!
//! ## FFI API (exposed to Kotlin via UniFFI)
//!
//! - [`proxy_init`] / [`proxy_shutdown`] — lifecycle.
//! - [`proxy_status`] / [`proxy_diagnostics`] — observability.
//! - [`proxy_fetch_image`] / [`proxy_fetch_images_batch`] — image fetching.
//! - [`proxy_fetch_url`] — generic tunnelled fetch.
//! - [`proxy_check_for_update`] — GitHub release check over the tunnel.
//! - [`proxy_clear_cache`] — drop the in-memory image cache.

pub mod config;
pub mod error;
pub mod http;
pub mod provisioning;
pub mod tunnel;
pub mod types;
pub mod update;

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

pub use config::ProxyConfig;
pub use error::ProxyError;
pub use types::{
    BatchImageResult, HttpFetchResponse, ImageResponse, ProxyStatus, UpdateResult, WarpDiagnostics,
};

use config::{FetchLimits, WarpConfig};
use provisioning::WarpProvisioner;
use tunnel::{ConnectionState, TunnelDiagnostics, TunnelManager};

uniffi::setup_scaffolding!();

/// Global proxy state, lazily initialized.
static PROXY_STATE: OnceLock<Mutex<Option<ProxyState>>> = OnceLock::new();

fn proxy_state() -> &'static Mutex<Option<ProxyState>> {
    PROXY_STATE.get_or_init(|| Mutex::new(None))
}

/// Lock the global state, mapping poisoning to a proxy error.
fn lock_state() -> Result<std::sync::MutexGuard<'static, Option<ProxyState>>, ProxyError> {
    proxy_state().lock().map_err(|_| ProxyError::TunnelError {
        details: "Proxy state lock poisoned".to_string(),
    })
}

/// Internal proxy state.
struct ProxyState {
    config: ProxyConfig,
    /// Shared so a fetch can run without holding the global lock. The `Arc` is
    /// genuine cross-section sharing (lock -> network -> lock), not a borrow hack.
    manager: Option<Arc<TunnelManager>>,
    cache: lru::LruCache<String, ImageResponse>,
    last_error: Option<String>,
}

impl ProxyState {
    /// Build the fetch limits from the current configuration.
    fn fetch_limits(&self) -> FetchLimits {
        FetchLimits {
            max_size: self.config.max_image_size,
            max_redirects: self.config.max_redirects,
            timeout_seconds: self.config.timeout_seconds,
            ..FetchLimits::default()
        }
    }
}

/// Run an async future to completion on a transient current-thread runtime.
///
/// Used only for the (direct-to-Cloudflare) WARP registration and config
/// persistence, which are inherently async via `reqwest`/`tokio::fs`.
fn block_on<F: std::future::Future>(future: F) -> Result<F::Output, ProxyError> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| ProxyError::InitializationFailed {
            details: format!("Failed to create async runtime: {e}"),
        })
        .map(|rt| rt.block_on(future))
}

/// Provision a fresh WARP account and persist it next to the proxy config.
fn provision_and_save(config: &ProxyConfig) -> Result<WarpConfig, ProxyError> {
    let config_path = config.config_file_path();
    block_on(async move {
        let provisioner = WarpProvisioner::new()?;
        let warp = provisioner.provision_new_account().await?;
        let contents = serde_json::to_string_pretty(&warp)?;
        tokio::fs::write(&config_path, contents).await?;
        Ok::<WarpConfig, ProxyError>(warp)
    })?
}

/// Ensure the tunnel manager exists, provisioning WARP on first use.
fn ensure_manager(state: &mut ProxyState) -> Result<Arc<TunnelManager>, ProxyError> {
    if let Some(manager) = &state.manager {
        return Ok(manager.clone());
    }

    let warp_config = match state.config.warp_config.clone() {
        Some(config) => config,
        None => {
            let config = provision_and_save(&state.config)?;
            state.config.warp_enabled = config.warp_enabled;
            state.config.endpoint_host = Some(config.peer.endpoint_host.clone());
            state.config.warp_config = Some(config.clone());
            config
        }
    };

    let manager = Arc::new(TunnelManager::start(warp_config)?);
    state.manager = Some(manager.clone());
    Ok(manager)
}

/// Validate that a URL is a fetchable http(s) URL.
fn validate_image_url(url: &str) -> Result<(), ProxyError> {
    let parsed = url::Url::parse(url).map_err(|e| ProxyError::InvalidUrl {
        url: url.to_string(),
        details: e.to_string(),
    })?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err(ProxyError::InvalidUrl {
            url: url.to_string(),
            details: "Only http:// and https:// URLs are supported".to_string(),
        });
    }
    Ok(())
}

/// Convert optional FFI headers into the ordered pairs the tunnel expects.
fn header_pairs(headers: Option<&HashMap<String, String>>) -> Vec<(String, String)> {
    headers
        .map(|map| map.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
        .unwrap_or_default()
}

/// Acquire the shared manager (initialising it if needed) under the lock,
/// returning a clone plus the current fetch limits.
fn acquire_manager() -> Result<(Arc<TunnelManager>, FetchLimits), ProxyError> {
    let mut guard = lock_state()?;
    let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
    let manager = ensure_manager(state)?;
    let limits = state.fetch_limits();
    Ok((manager, limits))
}

/// Record the most recent error for surfacing through [`proxy_status`].
fn record_error(message: &str) {
    if let Ok(mut guard) = lock_state() {
        if let Some(state) = guard.as_mut() {
            state.last_error = Some(message.to_string());
        }
    }
}

/// Initialize the image proxy.
///
/// Loads or creates persisted configuration and prepares the in-memory cache.
/// WARP provisioning and the WireGuard handshake are deferred until the first
/// fetch so initialization stays fast and works offline.
#[uniffi::export]
pub fn proxy_init(storage_path: String, max_cache_size: u32) -> Result<(), ProxyError> {
    let config = block_on(ProxyConfig::load_or_create(&storage_path))??;

    let cache_size = std::num::NonZeroUsize::new(max_cache_size as usize)
        .unwrap_or(std::num::NonZeroUsize::new(100).unwrap());

    let mut guard = lock_state()?;
    if guard.is_some() {
        return Ok(());
    }
    *guard = Some(ProxyState {
        config,
        manager: None,
        cache: lru::LruCache::new(cache_size),
        last_error: None,
    });
    Ok(())
}

/// Get the current proxy status.
#[uniffi::export]
pub fn proxy_status() -> Result<ProxyStatus, ProxyError> {
    let guard = lock_state()?;
    match guard.as_ref() {
        Some(state) => Ok(ProxyStatus {
            ready: true,
            warp_enabled: state.config.warp_enabled,
            tunnel_connected: state.manager.is_some(),
            endpoint: state.config.endpoint_host.clone(),
            last_error: state.last_error.clone(),
            cache_size: state.cache.len() as u32,
        }),
        None => Ok(ProxyStatus {
            ready: false,
            warp_enabled: false,
            tunnel_connected: false,
            endpoint: None,
            last_error: Some("Proxy not initialized".to_string()),
            cache_size: 0,
        }),
    }
}

/// Fetch a single image through the WARP tunnel.
#[uniffi::export]
pub fn proxy_fetch_image(
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<ImageResponse, ProxyError> {
    fetch_image(&url, headers.as_ref()).inspect_err(|e| {
        record_error(&e.to_string());
    })
}

/// Internal image fetch: cache-aware, tunnelled, content-validated.
fn fetch_image(
    url: &str,
    headers: Option<&HashMap<String, String>>,
) -> Result<ImageResponse, ProxyError> {
    validate_image_url(url)?;

    // Fast path: serve from cache without touching the network or the tunnel.
    {
        let mut guard = lock_state()?;
        let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
        if let Some(cached) = state.cache.get(url) {
            return Ok(ImageResponse {
                from_cache: true,
                ..cached.clone()
            });
        }
    }

    let (manager, limits) = acquire_manager()?;
    let outcome = manager.fetch(
        url.to_string(),
        header_pairs(headers),
        "image/*".to_string(),
        limits,
    )?;

    if !outcome.mime_type.starts_with("image/") {
        return Err(ProxyError::InvalidContentType {
            content_type: outcome.mime_type,
        });
    }

    let response = ImageResponse {
        mime_type: outcome.mime_type,
        data: outcome.body,
        from_cache: false,
        final_url: outcome.final_url,
    };

    if let Ok(mut guard) = lock_state() {
        if let Some(state) = guard.as_mut() {
            state.cache.put(url.to_string(), response.clone());
        }
    }

    Ok(response)
}

/// Fetch multiple images through the tunnel.
///
/// Requests are serviced by the single shared tunnel, so they are processed in
/// order; `max_concurrent` is accepted for API stability but currently advisory.
#[uniffi::export]
pub fn proxy_fetch_images_batch(
    urls: Vec<String>,
    _max_concurrent: u32,
) -> Result<Vec<BatchImageResult>, ProxyError> {
    let mut results = Vec::with_capacity(urls.len());
    for url in urls {
        match fetch_image(&url, None) {
            Ok(response) => results.push(BatchImageResult {
                url,
                success: true,
                response: Some(response),
                error: None,
            }),
            Err(e) => results.push(BatchImageResult {
                url,
                success: false,
                response: None,
                error: Some(e.to_string()),
            }),
        }
    }
    Ok(results)
}

/// Fetch an arbitrary URL through the tunnel (non-image content allowed).
#[uniffi::export]
pub fn proxy_fetch_url(
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<HttpFetchResponse, ProxyError> {
    let (manager, limits) = acquire_manager()?;
    let outcome = manager
        .fetch(
            url,
            header_pairs(headers.as_ref()),
            "*/*".to_string(),
            limits,
        )
        .inspect_err(|e| {
            record_error(&e.to_string());
        })?;
    Ok(HttpFetchResponse {
        status: outcome.status,
        mime_type: outcome.mime_type,
        data: outcome.body,
        final_url: outcome.final_url,
    })
}

/// Collect full WireGuard/WARP diagnostics, provisioning the tunnel if needed.
#[uniffi::export]
pub fn proxy_diagnostics() -> Result<WarpDiagnostics, ProxyError> {
    let manager = {
        let mut guard = lock_state()?;
        let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
        ensure_manager(state)?
    };
    let diagnostics = manager.diagnostics()?;
    Ok(to_ffi_diagnostics(diagnostics))
}

/// Map internal diagnostics into the FFI record.
fn to_ffi_diagnostics(d: TunnelDiagnostics) -> WarpDiagnostics {
    WarpDiagnostics {
        connection_state: match d.connection_state {
            ConnectionState::Connected => "connected".to_string(),
            ConnectionState::Disconnected => "disconnected".to_string(),
        },
        private_key: d.private_key,
        public_key: d.public_key,
        peer_public_key: d.peer_public_key,
        endpoint_host: d.endpoint_host,
        endpoint_ipv4: d.endpoint_ipv4,
        endpoint_ipv6: d.endpoint_ipv6,
        endpoint_port: d.endpoint_port,
        local_address_ipv4: d.local_address_ipv4,
        local_address_ipv6: d.local_address_ipv6,
        warp_enabled: d.warp_enabled,
        account_type: d.account_type,
        account_id: d.account_id,
        last_handshake_secs: d.last_handshake_secs,
        tx_bytes: d.tx_bytes,
        rx_bytes: d.rx_bytes,
        estimated_loss: d.estimated_loss,
        rtt_ms: d.rtt_ms,
    }
}

/// Check for a newer release through the tunnel.
///
/// Pass the running version (e.g. `"v1.2.3"`); `repo` defaults to the official
/// distribution slug when empty.
#[uniffi::export]
pub fn proxy_check_for_update(
    current_version: String,
    repo: Option<String>,
) -> Result<UpdateResult, ProxyError> {
    let repo = repo
        .filter(|r| !r.is_empty())
        .unwrap_or_else(|| update::DEFAULT_REPO.to_string());

    let (manager, _) = acquire_manager()?;
    let info = update::check_for_update(&manager, &current_version, &repo).inspect_err(|e| {
        record_error(&e.to_string());
    })?;

    Ok(UpdateResult {
        update_available: info.update_available,
        current_version: info.current_version,
        latest_version: info.latest_version,
        latest_tag: info.latest_tag,
        changelog: info.changelog,
        release_url: info.release_url,
    })
}

/// Shut down the proxy, dropping the tunnel and cache.
#[uniffi::export]
pub fn proxy_shutdown() -> Result<(), ProxyError> {
    let mut guard = lock_state()?;
    // Dropping the state drops the manager, which joins the worker thread.
    *guard = None;
    Ok(())
}

/// Clear the in-memory image cache.
#[uniffi::export]
pub fn proxy_clear_cache() -> Result<(), ProxyError> {
    let mut guard = lock_state()?;
    if let Some(state) = guard.as_mut() {
        state.cache.clear();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_validation_rejects_non_http_schemes() {
        assert!(validate_image_url("not-a-url").is_err());
        for url in [
            "ftp://example.com/x.png",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
        ] {
            assert!(matches!(
                validate_image_url(url),
                Err(ProxyError::InvalidUrl { .. })
            ));
        }
    }

    #[test]
    fn url_validation_accepts_http_and_https() {
        assert!(validate_image_url("http://example.com/x.png").is_ok());
        assert!(validate_image_url("https://example.com/x.png").is_ok());
    }

    #[test]
    fn header_pairs_handles_none_and_some() {
        assert!(header_pairs(None).is_empty());
        let mut map = HashMap::new();
        map.insert("X-A".to_string(), "1".to_string());
        let pairs = header_pairs(Some(&map));
        assert_eq!(pairs, vec![("X-A".to_string(), "1".to_string())]);
    }

    #[test]
    fn image_response_clone_preserves_fields() {
        let response = ImageResponse {
            mime_type: "image/png".to_string(),
            data: vec![0x89, 0x50, 0x4E, 0x47],
            from_cache: false,
            final_url: "https://example.com/image.png".to_string(),
        };
        let cloned = response.clone();
        assert_eq!(response.mime_type, cloned.mime_type);
        assert_eq!(response.data, cloned.data);
    }

    #[test]
    fn batch_result_variants() {
        let ok = BatchImageResult {
            url: "https://example.com/a.png".to_string(),
            success: true,
            response: Some(ImageResponse {
                mime_type: "image/png".to_string(),
                data: vec![1, 2, 3, 4],
                from_cache: false,
                final_url: "https://example.com/a.png".to_string(),
            }),
            error: None,
        };
        assert!(ok.success && ok.response.is_some() && ok.error.is_none());

        let err = BatchImageResult {
            url: "https://example.com/b.png".to_string(),
            success: false,
            response: None,
            error: Some("HTTP 404".to_string()),
        };
        assert!(!err.success && err.response.is_none() && err.error.is_some());
    }
}
