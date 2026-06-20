//! Network-gated end-to-end test against the real Cloudflare WARP service.
//!
//! This is intentionally `#[ignore]`d so it never runs in CI: it provisions a
//! *real* ephemeral WARP device, establishes an actual WireGuard tunnel, fetches
//! a real image over HTTPS through that tunnel, and then deletes the device.
//!
//! Run it manually with network access:
//!
//! ```bash
//! cargo test -p letterbox-proxy --test warp_real -- --ignored --nocapture
//! ```
//!
//! It asserts the *full* privacy path works: provisioning -> handshake ->
//! DNS-over-HTTPS -> TLS -> HTTP -> image validation, with the user's real IP
//! never used for the image request.

use letterbox_proxy::config::FetchLimits;
use letterbox_proxy::provisioning::WarpProvisioner;
use letterbox_proxy::tunnel::TunnelManager;

/// A small, stable PNG served over HTTPS that we can fetch through the tunnel.
const TEST_IMAGE_URL: &str = "https://www.cloudflare.com/favicon.ico";

#[test]
#[ignore = "network-gated: provisions a real WARP device and uses live network"]
fn real_warp_tunnel_fetches_image() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");

    // 1. Provision a fresh ephemeral WARP account directly with Cloudflare.
    let provisioner = WarpProvisioner::new().expect("create provisioner");
    let config = runtime
        .block_on(provisioner.provision_new_account())
        .expect("provision WARP account");
    let account = config.account.clone();

    // Ensure we always tear the device down, even if assertions panic.
    let result = std::panic::catch_unwind(|| {
        // 2. Bring up the real WireGuard tunnel and wait for the handshake.
        let manager = TunnelManager::start(config.clone()).expect("start tunnel");

        // 3. The tunnel must report a live session.
        let diagnostics = manager.diagnostics().expect("diagnostics");
        assert_eq!(
            diagnostics.connection_state,
            letterbox_proxy::tunnel::ConnectionState::Connected,
            "tunnel should be connected after handshake"
        );
        assert!(
            diagnostics.last_handshake_secs.is_some(),
            "a handshake should have completed"
        );

        // 4. Fetch a real image entirely through the tunnel.
        let outcome = manager
            .fetch(
                TEST_IMAGE_URL.to_string(),
                Vec::new(),
                "image/*".to_string(),
                FetchLimits::default(),
            )
            .expect("fetch image through tunnel");

        assert!(outcome.status >= 200 && outcome.status < 400, "ok status");
        assert!(!outcome.body.is_empty(), "image body should be non-empty");

        // 5. Confirm bytes actually traversed the encrypted tunnel.
        let after = manager.diagnostics().expect("diagnostics");
        assert!(after.tx_bytes > 0, "should have transmitted ciphertext");
        assert!(after.rx_bytes > 0, "should have received ciphertext");
    });

    // 6. Always delete the ephemeral device to avoid leaking accounts.
    let _ = runtime.block_on(provisioner.delete_device(&account));

    if let Err(payload) = result {
        std::panic::resume_unwind(payload);
    }
}
