//! Administrative and recovery FFI surface for the WARP tunnel.
//!
//! These operations exist so a user (or developer) can *understand and repair*
//! the tunnel when it misbehaves, without restarting the app:
//!
//! - [`proxy_stored_config`] reads the persisted WARP identity straight from
//!   state. It never provisions or handshakes, so it stays inspectable even when
//!   the tunnel is down — the exact moment visibility matters most.
//! - [`proxy_reset_identity`] refreshes the WARP identity: it tears down the
//!   live tunnel, best-effort deletes the old Cloudflare device, generates a new
//!   keypair, re-registers, and persists the fresh configuration.
//!
//! Both deliberately keep network I/O *outside* the global lock so a transient
//! failure can never poison it, and so a slow Cloudflare round-trip never blocks
//! unrelated callers.

use crate::config::WarpConfig;
use crate::error::ProxyError;
use crate::provisioning::WarpProvisioner;
use crate::types::WarpStoredConfig;
use crate::{block_on, lock_state, ProxyState};

/// Build the FFI snapshot from the currently held state.
///
/// Pure and lock-free: the caller already holds the guard. The public key is
/// derived from the persisted private key; a derivation failure (corrupt key)
/// degrades to an empty string rather than failing the whole snapshot, so the
/// rest of the diagnostics still reach the user.
fn snapshot(state: &ProxyState) -> WarpStoredConfig {
    let tunnel_active = state.manager.is_some();
    let config_file_path = state
        .config
        .config_file_path()
        .to_string_lossy()
        .into_owned();

    match &state.config.warp_config {
        Some(c) => WarpStoredConfig {
            has_config: true,
            tunnel_active,
            account_id: c.account.account_id.clone(),
            license_key: c.account.license_key.clone(),
            private_key: c.account.private_key.clone(),
            public_key: WarpProvisioner::public_key_from_private(&c.account.private_key)
                .unwrap_or_default(),
            peer_public_key: c.peer.public_key.clone(),
            endpoint_host: c.peer.endpoint_host.clone(),
            endpoint_ipv4: c.peer.endpoint_ipv4.clone(),
            endpoint_port: c.peer.endpoint_port,
            local_address_ipv4: c.interface.address_ipv4.clone(),
            warp_enabled: c.warp_enabled,
            account_type: c.account_type.clone(),
            last_updated_secs: c.last_updated,
            config_file_path,
        },
        None => WarpStoredConfig {
            has_config: false,
            tunnel_active,
            account_id: String::new(),
            license_key: String::new(),
            private_key: String::new(),
            public_key: String::new(),
            peer_public_key: String::new(),
            endpoint_host: String::new(),
            endpoint_ipv4: String::new(),
            endpoint_port: 0,
            local_address_ipv4: String::new(),
            warp_enabled: false,
            account_type: String::new(),
            last_updated_secs: 0,
            config_file_path,
        },
    }
}

/// Read the persisted WARP identity and tunnel configuration.
///
/// Never touches the network: this works whether or not the tunnel has
/// connected, making it the primary tool for diagnosing a tunnel that refuses
/// to come up.
#[uniffi::export]
pub fn proxy_stored_config() -> Result<WarpStoredConfig, ProxyError> {
    let guard = lock_state();
    let state = guard.as_ref().ok_or(ProxyError::NotInitialized)?;
    Ok(snapshot(state))
}

/// Refresh the WARP identity from scratch and persist it.
///
/// Steps, in order:
/// 1. Tear down the live tunnel and clear the last error (under the lock).
/// 2. Best-effort delete the old Cloudflare device (outside the lock).
/// 3. Generate a new keypair, register, fetch config, enable WARP, and write
///    the new `warp_config.json` (outside the lock).
/// 4. Swap in the new configuration and force the tunnel to rebuild on next use.
///
/// The new tunnel is *not* eagerly reconnected: the caller can immediately
/// follow up with [`crate::proxy_diagnostics`] to rebuild and verify it.
#[uniffi::export]
pub fn proxy_reset_identity() -> Result<WarpStoredConfig, ProxyError> {
    // Phase 1: snapshot what we need and drop the existing tunnel under the lock.
    let (storage_path, old_account) = {
        let mut guard = lock_state();
        let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
        // Dropping the manager's last `Arc` joins its worker thread.
        state.manager = None;
        state.last_error = None;
        let old_account = state.config.warp_config.as_ref().map(|c| c.account.clone());
        (state.config.storage_path.clone(), old_account)
    };

    // Phase 2 + 3: network I/O and persistence run without the lock held, so a
    // failure here can never poison the global mutex.
    let new_config = block_on(async move {
        let provisioner = WarpProvisioner::new()?;

        if let Some(account) = old_account {
            // Lingering devices are harmless but untidy; never fail the reset on
            // a cleanup error (the old token may already be invalid).
            if let Err(e) = provisioner.delete_device(&account).await {
                log::warn!("Failed to delete old WARP device during reset: {e}");
            }
        }

        let warp = provisioner.provision_new_account().await?;
        let contents = serde_json::to_string_pretty(&warp)?;
        let config_path = storage_path.join("warp_config.json");
        tokio::fs::write(&config_path, contents).await?;
        Ok::<WarpConfig, ProxyError>(warp)
    })??;

    // Phase 4: install the fresh configuration under the lock.
    let mut guard = lock_state();
    let state = guard.as_mut().ok_or(ProxyError::NotInitialized)?;
    state.config.warp_enabled = new_config.warp_enabled;
    state.config.endpoint_host = Some(new_config.peer.endpoint_host.clone());
    state.config.warp_config = Some(new_config);
    state.manager = None;
    Ok(snapshot(state))
}
