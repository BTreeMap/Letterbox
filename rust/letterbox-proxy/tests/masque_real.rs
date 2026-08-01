//! Network-gated end-to-end test of the MASQUE transport against real Cloudflare.
//!
//! `#[ignore]`d like `warp_real`, so the live path never gates CI. Run it with
//! network access:
//!
//! ```bash
//! cargo test -p letterbox-proxy --test masque_real -- --ignored --nocapture
//! ```
//!
//! What this proves that the unit tests cannot: that Cloudflare accepts the
//! P-256 enrolment, that the endpoint completes a QUIC handshake **with
//! `api.cloudflare.com` substituted as the SNI**, that the CONNECT-IP flow
//! opens, and that a real image comes back through it.
//!
//! The SNI substitution is the part most worth testing here. It is effectively
//! domain fronting within Cloudflare's edge, so whether it works is a
//! server-side policy question that no amount of local reasoning settles.

use std::time::Duration;

use letterbox_proxy::config::FetchLimits;
use letterbox_proxy::provisioning::WarpProvisioner;
use letterbox_proxy::tunnel::masque::{MASQUE_ENDPOINT_IPV4, MASQUE_SNI};
use letterbox_proxy::tunnel::TunnelManager;

/// A small, stable image served over HTTPS.
const TEST_IMAGE_URL: &str = "https://www.cloudflare.com/favicon.ico";

#[test]
#[ignore = "network-gated: provisions a real WARP device and uses live network"]
fn real_masque_tunnel_fetches_image() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");

    let provisioner = WarpProvisioner::new().expect("create provisioner");
    let config = runtime
        .block_on(provisioner.provision_new_account())
        .expect("provision WARP account");
    let account = config.account.clone();

    let result = std::panic::catch_unwind(|| {
        // Enrolment is best-effort inside provisioning, so a missing credential
        // here means Cloudflare refused the PATCH — a different failure from the
        // tunnel not coming up, and worth distinguishing.
        let credentials = config
            .masque
            .as_ref()
            .expect("MASQUE enrolment must succeed for this test to mean anything");
        assert!(
            !credentials.ec_private_key_der.is_empty(),
            "enrolment returned an empty private key"
        );
        assert!(
            credentials.decode_endpoint_key().expect("decodable").len() > 32,
            "endpoint key should be an SPKI structure, not a raw point"
        );

        println!("enrolled MASQUE key; connecting to {MASQUE_ENDPOINT_IPV4}:443 as {MASQUE_SNI}");

        let manager = TunnelManager::start(config.clone()).expect("start tunnel");

        let diagnostics = manager.diagnostics().expect("diagnostics");
        assert_eq!(
            diagnostics.protocol, "masque",
            "this test is meaningless if the tunnel silently fell back to WireGuard"
        );
        assert_eq!(
            diagnostics.connection_state,
            letterbox_proxy::tunnel::ConnectionState::Connected,
            "MASQUE tunnel should be connected; if this fails with the substituted \
             SNI, retry with consumer-masque.cloudflareclient.com to tell an SNI \
             rejection apart from a broken tunnel"
        );

        let outcome = manager
            .fetch(letterbox_proxy::tunnel::FetchRequest::new(
                TEST_IMAGE_URL,
                letterbox_proxy::tunnel::http1::ClientProfile::browser("image/*"),
                FetchLimits::default(),
            ))
            .expect("fetch image through MASQUE tunnel");

        assert!(
            (200..400).contains(&outcome.status),
            "unexpected status {}",
            outcome.status
        );
        assert!(!outcome.body.is_empty(), "image body should be non-empty");

        let after = manager.diagnostics().expect("diagnostics");
        assert!(after.tx_bytes > 0, "should have transmitted");
        assert!(after.rx_bytes > 0, "should have received");
        assert!(
            after.last_handshake_secs.is_some_and(|s| s < 600),
            "connect time should be recent and finite, got {:?}",
            after.last_handshake_secs
        );

        println!(
            "fetched {} bytes through MASQUE (tx {}, rx {})",
            outcome.body.len(),
            after.tx_bytes,
            after.rx_bytes
        );
    });

    let _ = runtime.block_on(provisioner.delete_device(&account));

    if let Err(payload) = result {
        std::panic::resume_unwind(payload);
    }
}

/// Isolates the one question local reasoning cannot answer: does the endpoint
/// complete a QUIC handshake when the ClientHello carries a name that is not
/// its own?
///
/// Kept separate from the full test so an SNI rejection is distinguishable from
/// a failure anywhere else in the stack.
#[test]
#[ignore = "network-gated: performs a live QUIC handshake"]
fn masque_endpoint_accepts_substituted_sni() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");

    let provisioner = WarpProvisioner::new().expect("create provisioner");
    let config = runtime
        .block_on(provisioner.provision_new_account())
        .expect("provision WARP account");
    let account = config.account.clone();

    let result = std::panic::catch_unwind(|| {
        assert!(
            config.masque.is_some(),
            "enrolment must succeed before the handshake can be tested"
        );

        let manager = TunnelManager::start(config.clone()).expect("start tunnel");

        // Poll rather than assert immediately: the session connects
        // asynchronously and `start` returns before the flow is open.
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        let mut connected = false;
        while std::time::Instant::now() < deadline {
            if manager.diagnostics().is_ok_and(|d| {
                d.connection_state == letterbox_proxy::tunnel::ConnectionState::Connected
            }) {
                connected = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(500));
        }

        assert!(
            connected,
            "no CONNECT-IP flow within 30s using SNI {MASQUE_SNI}"
        );
        println!("endpoint accepted SNI {MASQUE_SNI}");
    });

    let _ = runtime.block_on(provisioner.delete_device(&account));

    if let Err(payload) = result {
        std::panic::resume_unwind(payload);
    }
}
