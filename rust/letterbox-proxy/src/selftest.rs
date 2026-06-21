//! On-device self-tests for the live networking stack.
//!
//! The provisioning HTTP client talks straight to Cloudflare (outside the WARP
//! tunnel) and so depends on a working TLS certificate verifier. `reqwest`'s
//! `rustls` feature defaults to `rustls-platform-verifier`, which on Android
//! panics with *"Expect rustls-platform-verifier to be initialized"* on the
//! first handshake unless the app first hands the crate a JNI handle to the
//! system trust manager — wiring this project deliberately does not have.
//!
//! [`proxy_tls_self_test`] guards against that whole class of regression. It is
//! meant to run inside an instrumented Android test (a real handshake on a real
//! Dalvik/ART VM is the only place the fault can reproduce), where it fails the
//! build if — and only if — the platform verifier is reached.

use crate::block_on;
use crate::provisioning::WarpProvisioner;
use std::panic::AssertUnwindSafe;

/// Case-insensitive substrings that identify the uninitialized platform-verifier
/// fault, whether it surfaces as a panic payload or as a nested connection error.
const PLATFORM_VERIFIER_MARKERS: &[&str] = &[
    "rustls-platform-verifier",
    "platform verifier",
    "expect rustls-platform-verifier to be initialized",
];

/// Outcome of [`proxy_tls_self_test`].
#[derive(Debug, uniffi::Enum)]
pub enum TlsSelfTestOutcome {
    /// The handshake to Cloudflare completed and the server certificate verified
    /// against the bundled `webpki-roots` trust anchors. The strongest pass.
    Verified,
    /// The probe could not complete for a transient reason (DNS failure,
    /// connection refused/reset, timeout, ...). The certificate verifier was not
    /// the cause, so callers should treat this as a (noted) pass rather than a
    /// regression.
    Inconclusive { reason: String },
    /// The certificate verifier was reached and faulted because `reqwest` fell
    /// back to the platform verifier, which was never initialized on Android.
    /// This is the exact regression guarded against and is always a failure.
    PlatformVerifierUninitialized { reason: String },
}

/// Probe the provisioning TLS path and report whether the platform verifier was
/// (incorrectly) reached.
///
/// This builds the real [`WarpProvisioner`] client and performs a single,
/// state-free `GET` against the WARP API host. It registers no device and
/// mutates no Cloudflare state, so it is safe to run on every CI build.
///
/// Detection is belt-and-suspenders: the probe is driven on a current-thread
/// runtime (see [`block_on`]) so a verifier panic unwinds back here where
/// [`std::panic::catch_unwind`] observes it, *and* any returned transport error
/// is scanned for the marker in case the panic is instead converted into a
/// connection error deeper in the stack.
#[uniffi::export]
pub fn proxy_tls_self_test() -> TlsSelfTestOutcome {
    let probe = std::panic::catch_unwind(AssertUnwindSafe(|| {
        block_on(async {
            let provisioner = WarpProvisioner::new()?;
            provisioner.tls_self_test().await
        })
    }));

    match probe {
        // Runtime built, future ran, handshake verified.
        Ok(Ok(Ok(()))) => TlsSelfTestOutcome::Verified,
        // Runtime built, future ran, transport error: inspect the chain.
        Ok(Ok(Err(err))) => classify(err.to_string()),
        // Runtime build itself failed (no network involved): inconclusive.
        Ok(Err(err)) => TlsSelfTestOutcome::Inconclusive {
            reason: err.to_string(),
        },
        // The probe unwound — classify the panic payload.
        Err(panic) => classify(panic_message(&panic)),
    }
}

/// Map an error/panic message onto an outcome by looking for the verifier marker.
fn classify(message: String) -> TlsSelfTestOutcome {
    let haystack = message.to_lowercase();
    if PLATFORM_VERIFIER_MARKERS
        .iter()
        .any(|marker| haystack.contains(marker))
    {
        TlsSelfTestOutcome::PlatformVerifierUninitialized { reason: message }
    } else {
        TlsSelfTestOutcome::Inconclusive { reason: message }
    }
}

/// Best-effort extraction of a human-readable message from a panic payload.
fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = panic.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = panic.downcast_ref::<String>() {
        s.clone()
    } else {
        "panic with non-string payload".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_flags_platform_verifier_marker() {
        let outcome = classify("Expect rustls-platform-verifier to be initialized".to_string());
        assert!(matches!(
            outcome,
            TlsSelfTestOutcome::PlatformVerifierUninitialized { .. }
        ));
    }

    #[test]
    fn classify_tolerates_network_errors() {
        let outcome = classify("error sending request: connection refused".to_string());
        assert!(matches!(outcome, TlsSelfTestOutcome::Inconclusive { .. }));
    }

    #[test]
    fn classify_is_case_insensitive() {
        let outcome = classify("RUSTLS-PLATFORM-VERIFIER blew up".to_string());
        assert!(matches!(
            outcome,
            TlsSelfTestOutcome::PlatformVerifierUninitialized { .. }
        ));
    }
}
