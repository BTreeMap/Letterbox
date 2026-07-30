//! Network-gated test of the upgrade path an existing install takes.
//!
//! ```bash
//! cargo test -p letterbox-proxy --test masque_upgrade -- --ignored --nocapture
//! ```
//!
//! Every device provisioned before MASQUE support has a stored configuration
//! with no MASQUE credentials, and `provision_new_account` never runs again for
//! it. Unless enrolment also works on an *already registered* device, those
//! installs stay on WireGuard for ever — the transport this migration exists to
//! leave, and a failure that would go unnoticed precisely because everything
//! still appears to work.

use letterbox_proxy::provisioning::WarpProvisioner;

#[test]
#[ignore = "network-gated: registers and deletes a real WARP device"]
fn already_registered_device_can_enrol_masque() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build tokio runtime");

    let provisioner = WarpProvisioner::new().expect("create provisioner");
    let mut config = runtime
        .block_on(provisioner.provision_new_account())
        .expect("provision WARP account");
    let account = config.account.clone();

    // Simulate a configuration written before MASQUE support existed.
    config.masque = None;

    // Delete the device before asserting, so a failure cannot leak an account.
    let outcome = runtime.block_on(provisioner.enroll_masque_key(&account));
    let _ = runtime.block_on(provisioner.delete_device(&account));

    let credentials = outcome.expect("enrolment must succeed on an already-registered device");
    assert!(
        !credentials.ec_private_key_der.is_empty(),
        "enrolment returned an empty private key"
    );
    assert!(
        credentials.decode_endpoint_key().expect("decodable").len() > 32,
        "endpoint key should be an SPKI structure, not a raw point"
    );

    println!("legacy account enrolled MASQUE credentials");
}
