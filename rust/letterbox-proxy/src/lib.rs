//! # Letterbox Image Proxy
//!
//! Privacy-preserving image proxy and update checker built on Cloudflare WARP
//! over a userspace MASQUE tunnel.
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
//! FFI -> TunnelManager (worker thread) -> http -> tls/dns -> smoltcp -> MASQUE -> UDP
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

pub mod admin;
pub mod config;
pub mod error;
pub mod http;
pub mod provisioning;
pub mod selftest;
pub mod tunnel;
pub mod types;
pub mod update;

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

pub use config::ProxyConfig;
pub use error::ProxyError;
pub use types::{
    BatchImageResult, HttpFetchResponse, ImageResponse, ProxyStatus, UpdateResult, WarpDiagnostics,
    WarpStoredConfig,
};

use config::{FetchLimits, WarpConfig};
use provisioning::WarpProvisioner;
use tunnel::{TunnelDiagnostics, TunnelManager};

uniffi::setup_scaffolding!();

/// Cache capacity used when the caller asks for zero entries.
const DEFAULT_CACHE_ENTRIES: std::num::NonZeroUsize = std::num::NonZeroUsize::new(100).unwrap();

/// Global proxy state, lazily initialized.
static PROXY_STATE: OnceLock<Mutex<Option<ProxyState>>> = OnceLock::new();

fn proxy_state() -> &'static Mutex<Option<ProxyState>> {
    PROXY_STATE.get_or_init(|| Mutex::new(None))
}

/// Lock the global state, recovering from poisoning.
///
/// A panic while a guard is held — e.g. during WARP provisioning or tunnel
/// start-up, both of which run under this lock — poisons the `Mutex`. The
/// protected [`ProxyState`] nonetheless stays structurally valid: at worst the
/// tunnel manager is absent and gets rebuilt on the next fetch. Reclaiming the
/// guard via [`PoisonError::into_inner`] lets the proxy retry and surface the
/// real error, instead of permanently reporting a misleading "lock poisoned"
/// failure that masks the original cause and bricks the proxy until restart.
pub(crate) fn lock_state() -> std::sync::MutexGuard<'static, Option<ProxyState>> {
    proxy_state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Internal proxy state.
pub(crate) struct ProxyState {
    pub(crate) config: ProxyConfig,
    /// Shared so a fetch can run without holding the global lock. The `Arc` is
    /// genuine cross-section sharing (lock -> network -> lock), not a borrow hack.
    pub(crate) manager: Option<Arc<TunnelManager>>,
    cache: lru::LruCache<String, ImageResponse>,
    pub(crate) last_error: Option<String>,
}

impl ProxyState {
    /// Project the configuration onto the limits a single fetch needs.
    ///
    /// Total: every field of [`FetchLimits`] has exactly one source in the
    /// configuration, so there is nothing left to default.
    fn fetch_limits(&self) -> FetchLimits {
        FetchLimits {
            max_size: self.config.max_image_size,
            max_redirects: self.config.max_redirects,
            timeout_seconds: self.config.timeout_seconds,
        }
    }
}

/// Run a fallible async operation to completion on a transient current-thread
/// runtime.
///
/// Used only for the (direct-to-Cloudflare) WARP registration and config
/// persistence, which are inherently async via `reqwest`/`tokio::fs`.
///
/// The future's own failure and the failure to build a runtime for it are both
/// [`ProxyError`], so they are joined into one `Result` here rather than handed
/// to every caller as a nested pair to unwrap twice.
pub(crate) fn block_on<T, F>(future: F) -> Result<T, ProxyError>
where
    F: std::future::Future<Output = Result<T, ProxyError>>,
{
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| ProxyError::InitializationFailed {
            details: format!("Failed to create async runtime: {e}"),
        })?
        .block_on(future)
}

/// Ensure the tunnel manager exists, provisioning WARP on first use.
fn ensure_manager(state: &mut ProxyState) -> Result<Arc<TunnelManager>, ProxyError> {
    if let Some(manager) = &state.manager {
        return Ok(manager.clone());
    }

    let warp_config = match state.config.warp_config.clone() {
        Some(config) => upgrade_to_masque(state, config),
        None => {
            let config = block_on(async { WarpProvisioner::new()?.provision_new_account().await })?;
            // Installing and persisting are one step: a config held in memory
            // but not on disk would silently re-provision on the next launch,
            // stranding a device registration on Cloudflare's side each time.
            block_on(state.config.update_warp_config(config.clone()))?;
            config
        }
    };

    let manager = Arc::new(TunnelManager::start(warp_config)?);
    state.manager = Some(manager.clone());
    Ok(manager)
}

/// Enrol a MASQUE key on an account that predates MASQUE support.
///
/// Without this an existing install keeps its stored WireGuard-only
/// configuration for ever: `provision_new_account` enrols during provisioning,
/// but a device provisioned before that existed never provisions again. Since
/// WireGuard is the transport we are moving off, "works but never upgrades" is
/// the failure mode that would go unnoticed longest.
///
/// Returns the configuration to use. Enrolment is best-effort and its failure is
/// not this function's to report: the caller has a working WireGuard
/// configuration either way, and refusing to connect because an *optimisation*
/// failed would be worse than the blocked-transport problem it solves.
fn upgrade_to_masque(state: &mut ProxyState, config: WarpConfig) -> WarpConfig {
    if config.masque.is_some() {
        return config;
    }

    let enrolled = block_on(async {
        WarpProvisioner::new()?
            .enroll_masque_key(&config.account)
            .await
    });

    let credentials = match enrolled {
        Ok(credentials) => credentials,
        Err(e) => {
            log::warn!("MASQUE upgrade failed, staying on the stored configuration: {e}");
            return config;
        }
    };

    let upgraded = WarpConfig {
        masque: Some(credentials),
        ..config
    };
    state.config.warp_config = Some(upgraded.clone());
    if let Err(e) = block_on(state.config.save()) {
        // The tunnel still comes up; only the upgrade fails to stick, and the
        // next launch retries it.
        log::warn!("failed to persist MASQUE credentials: {e}");
    }
    log::info!("upgraded existing account to MASQUE");
    upgraded
}

/// Convert optional FFI headers into the ordered pairs the tunnel expects.
///
/// Consumes the map: the caller owns it and has no further use for it, so the
/// strings move rather than being cloned twice over.
fn header_pairs(headers: Option<HashMap<String, String>>) -> Vec<(String, String)> {
    headers.map(Vec::from_iter).unwrap_or_default()
}

/// Acquire the shared manager (initialising it if needed) under the lock,
/// returning a clone plus the current fetch limits.
fn acquire_manager() -> Result<(Arc<TunnelManager>, FetchLimits), ProxyError> {
    let mut guard = lock_state();
    let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
    let manager = ensure_manager(state)?;
    let limits = state.fetch_limits();
    Ok((manager, limits))
}

/// Record the most recent error for surfacing through [`proxy_status`].
fn record_error(message: &str) {
    let mut guard = lock_state();
    if let Some(state) = guard.as_mut() {
        state.last_error = Some(message.to_string());
    }
}

/// Initialize the image proxy.
///
/// Loads or creates persisted configuration and prepares the in-memory cache.
/// WARP provisioning and the tunnel handshake are deferred until the first
/// fetch so initialization stays fast and works offline.
#[uniffi::export]
pub fn proxy_init(storage_path: String, max_cache_size: u32) -> Result<(), ProxyError> {
    let config = block_on(ProxyConfig::load_or_create(&storage_path))?;

    // A zero-capacity LRU cannot exist, so a caller asking for one gets the
    // default rather than a panic.
    let cache_size =
        std::num::NonZeroUsize::new(max_cache_size as usize).unwrap_or(DEFAULT_CACHE_ENTRIES);

    let mut guard = lock_state();
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
///
/// Never fails: "not initialized" is a status, not an error, which is what the
/// `ready` flag reports.
#[uniffi::export]
pub fn proxy_status() -> Result<ProxyStatus, ProxyError> {
    Ok(lock_state().as_ref().map_or_else(
        || ProxyStatus {
            last_error: Some(ProxyError::NotInitialized.to_string()),
            ..ProxyStatus::default()
        },
        |state| ProxyStatus {
            ready: true,
            warp_enabled: state.config.warp_enabled(),
            tunnel_connected: state.manager.is_some(),
            endpoint: state.config.endpoint_host().map(str::to_string),
            last_error: state.last_error.clone(),
            cache_size: state.cache.len() as u32,
        },
    ))
}

/// Fetch a single image through the WARP tunnel.
#[uniffi::export]
pub fn proxy_fetch_image(
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<ImageResponse, ProxyError> {
    fetch_image(&url, headers).inspect_err(|e| {
        record_error(&e.to_string());
    })
}

/// Internal image fetch: cache-aware, tunnelled, content-validated.
fn fetch_image(
    url: &str,
    headers: Option<HashMap<String, String>>,
) -> Result<ImageResponse, ProxyError> {
    // Reject unsupported schemes before touching the cache or the tunnel, using
    // the same parser the fetch itself will use so the two cannot disagree.
    http::parse_and_validate(url)?;

    // Fast path: serve from cache without touching the network or the tunnel.
    {
        let mut guard = lock_state();
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

    {
        let mut guard = lock_state();
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
    // Each element's outcome is independent, so a failure is a value in the
    // result list rather than a short-circuit: one broken image must not hide
    // the rest.
    Ok(urls
        .into_iter()
        .map(|url| {
            let outcome = fetch_image(&url, None);
            BatchImageResult::new(url, outcome)
        })
        .collect())
}

/// Fetch an arbitrary URL through the tunnel (non-image content allowed).
#[uniffi::export]
pub fn proxy_fetch_url(
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<HttpFetchResponse, ProxyError> {
    let (manager, limits) = acquire_manager()?;
    let outcome = manager
        .fetch(url, header_pairs(headers), "*/*".to_string(), limits)
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

/// Collect full tunnel/WARP diagnostics, provisioning the tunnel if needed.
#[uniffi::export]
pub fn proxy_diagnostics() -> Result<WarpDiagnostics, ProxyError> {
    let manager = {
        let mut guard = lock_state();
        let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
        ensure_manager(state)?
    };
    let diagnostics = manager.diagnostics()?;
    Ok(to_ffi_diagnostics(diagnostics))
}

/// Map internal diagnostics into the FFI record.
fn to_ffi_diagnostics(d: TunnelDiagnostics) -> WarpDiagnostics {
    WarpDiagnostics {
        connection_state: d.connection_state.as_str().to_string(),
        protocol: d.protocol.to_string(),
        private_key: d.private_key,
        public_key: d.public_key,
        peer_public_key: d.peer_public_key,
        endpoint_host: d.endpoint_host,
        endpoint_ipv4: d.endpoint_ipv4,
        endpoint_port: d.endpoint_port,
        local_address_ipv4: d.local_address_ipv4,
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
    let mut guard = lock_state();
    // Dropping the state drops the manager, which joins the worker thread.
    *guard = None;
    Ok(())
}

/// Clear the in-memory image cache.
#[uniffi::export]
pub fn proxy_clear_cache() -> Result<(), ProxyError> {
    let mut guard = lock_state();
    if let Some(state) = guard.as_mut() {
        state.cache.clear();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // URL admission is tested where it is decided, in `http::parse_and_validate`.

    #[test]
    fn header_pairs_handles_none_and_some() {
        assert!(header_pairs(None).is_empty());
        let map = HashMap::from([("X-A".to_string(), "1".to_string())]);
        assert_eq!(
            header_pairs(Some(map)),
            vec![("X-A".to_string(), "1".to_string())]
        );
    }

    /// The three outcome fields must never disagree, whichever way the fetch went.
    #[test]
    fn batch_result_correlates_its_outcome_fields() {
        let ok = BatchImageResult::new(
            "https://example.com/a.png".to_string(),
            Ok(ImageResponse {
                mime_type: "image/png".to_string(),
                data: vec![1, 2, 3, 4],
                from_cache: false,
                final_url: "https://example.com/a.png".to_string(),
            }),
        );
        assert!(ok.success && ok.response.is_some() && ok.error.is_none());

        let err = BatchImageResult::new(
            "https://example.com/b.png".to_string(),
            Err(ProxyError::HttpError {
                status_code: 404,
                details: "Not found".to_string(),
            }),
        );
        assert!(!err.success && err.response.is_none());
        assert!(err.error.expect("error text").contains("404"));
    }

    /// Before initialization the status is a value, not an error: the FFI call
    /// must succeed and say `ready: false`.
    #[test]
    fn status_before_init_is_not_ready() {
        let status = ProxyStatus::default();
        assert!(!status.ready);
        assert!(!status.tunnel_connected);
        assert_eq!(status.cache_size, 0);
        assert_eq!(status.endpoint, None);
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
}
