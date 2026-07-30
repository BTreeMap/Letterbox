//! Transport-independent tunnel counters.
//!
//! These lived in the WireGuard transport when it was the only one. They are not
//! WireGuard concepts — every transport moves bytes and either has a live
//! session or does not — so they outlived it here rather than moving into
//! MASQUE and having to move again.

use std::time::Duration;

/// Live statistics for whatever transport is carrying the tunnel.
#[derive(Debug, Clone, Default)]
pub struct TunnelStats {
    /// Time since the session became usable, if it has.
    ///
    /// `None` means "not connected", which is why this is an `Option` rather
    /// than a zero: a stopped clock and a tunnel that never came up are
    /// different facts, and the diagnostics screen shows them differently.
    pub since_handshake: Option<Duration>,
    /// Total plaintext bytes transmitted into the tunnel.
    pub tx_bytes: u64,
    /// Total plaintext bytes received from the tunnel.
    pub rx_bytes: u64,
    /// Estimated packet loss in `[0.0, 1.0]`.
    pub estimated_loss: f32,
    /// Estimated round-trip time in milliseconds, if measured.
    pub rtt_ms: Option<u32>,
}
